import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("org.springframework.boot") version "3.2.4" apply false
    id("io.spring.dependency-management") version "1.1.4" apply false
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.spring") version "1.9.24" apply false
    kotlin("plugin.jpa") version "1.9.24" apply false
    // Static analysis: aggregates the whole multi-project for SonarQube (CI `sonar` task).
    id("org.sonarqube") version "5.1.0.4882"
    // Applied to the root so the aggregate JacocoReport/JacocoCoverageVerification tasks below get a
    // configured jacocoClasspath (the per-module jacoco plugin only configures its own project).
    jacoco
}

allprojects {
    group = "com.circleguard"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    // Code coverage: JaCoCo XML feeds SonarQube's sonar.coverage.jacoco.xmlReportPaths.
    apply(plugin = "jacoco")
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform {
            if (!project.hasProperty("integration")) {
                excludeTags("integration")
            }
        }
        // Always refresh the coverage report after tests so CI can publish/scan it.
        finalizedBy(tasks.named("jacocoTestReport"))
        if (!project.hasProperty("integration")) {
            // JUnit's tag filter runs *after* test classes are loaded, so any class with a
            // static `@Container` field can still trigger Testcontainers' Docker discovery
            // (DockerClientProviderStrategy) and fail with `initializationError` when the
            // agent has no Docker. We exclude the bytecode by path/name to prevent loading.
            exclude("**/integration/**")
            exclude("**/*IntegrationTest.class")
            exclude("**/HealthStatusReevaluationTest.class")
            exclude("**/AdministrativeCorrectionTest.class")
            exclude("**/PromotionPerformanceTest.class")
        }
        if (project.hasProperty("integration")) {
            // Testcontainers lee DOCKER_HOST; útil en Jenkins con docker.sock montado.
            environment(
                "DOCKER_HOST",
                System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock",
            )
        }
    }

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

// SonarQube analysis (root aggregates all modules). Server URL + token are supplied by CI via
// `-Dsonar.host.url` / `SONAR_TOKEN` (see ci/Jenkinsfile.*); the task is a no-op locally without them.
sonar {
    properties {
        property("sonar.projectKey", "circleguard")
        property("sonar.projectName", "CircleGuard")
        property("sonar.coverage.jacoco.xmlReportPaths", "**/build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.junit.reportPaths", "**/build/test-results/test")
    }
}

configure(subprojects.filter { it.name != "e2e-tests" }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        // Observability: Actuator exposes health/info/metrics; Micrometer's Prometheus
        // registry renders /actuator/prometheus for scraping. Shared by all 8 services.
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"("io.micrometer:micrometer-registry-prometheus")
        // Distributed tracing: Micrometer Tracing bridges Observation spans to OpenTelemetry,
        // and the OTLP exporter ships them to Jaeger (OTLP/HTTP on :4318).
        "implementation"("io.micrometer:micrometer-tracing-bridge-otel")
        "implementation"("io.opentelemetry:opentelemetry-exporter-otlp")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("com.h2database:h2")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict")
            jvmTarget = "21"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Aggregated code coverage across all microservice modules.
// Each module already emits JaCoCo XML (consumed by SonarQube). This root task merges every
// module's execution data into ONE human-readable XML + HTML report, and a companion gate
// verifies a minimum line coverage. Run via the Docker helper on this Windows host (native
// gradlew is loopback-blocked — see scripts/verify-local-docker.ps1 / HANDOFF.md §4):
//   ./scripts/verify-local-docker.ps1 test jacocoAggregatedReport jacocoCoverageVerification
// Cross-project sourceSet/exec lookups are wrapped in provider {} so they resolve at execution
// time (after every subproject has been configured), not during root-script evaluation.
// ---------------------------------------------------------------------------------------------
val coveredProjects = subprojects.filter { it.name != "e2e-tests" }

// Class-file noise that isn't meaningfully unit-tested; excluded so the % reflects real logic.
val coverageClassExcludes = listOf(
    "**/*Application.class", "**/config/**", "**/dto/**", "**/model/**", "**/entity/**",
)

val jacocoAggregatedReport = tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    description = "Aggregates JaCoCo coverage from all microservice modules into one XML + HTML report."
    dependsOn(coveredProjects.map { "${it.path}:test" })

    executionData.setFrom(provider {
        files(coveredProjects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
            .filter { it.exists() }
    })
    sourceDirectories.setFrom(provider {
        files(coveredProjects.mapNotNull {
            it.extensions.findByType(SourceSetContainer::class.java)?.findByName("main")?.allSource?.srcDirs
        })
    })
    classDirectories.setFrom(provider {
        files(coveredProjects.mapNotNull {
            it.extensions.findByType(SourceSetContainer::class.java)?.findByName("main")?.output
        }).asFileTree.matching { exclude(coverageClassExcludes) }
    })

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/jacocoAggregatedReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
    }
}

// Coverage gate. Threshold is modest by default (raise as the suite grows) and overridable:
//   ./scripts/verify-local-docker.ps1 jacocoCoverageVerification -PcoverageMin=0.40
val coverageMin = (findProperty("coverageMin") as String? ?: "0.30").toBigDecimal()
tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    group = "verification"
    description = "Fails the build if aggregated LINE coverage is below -PcoverageMin (default 0.30)."
    dependsOn(jacocoAggregatedReport)
    executionData.setFrom(provider {
        files(coveredProjects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
            .filter { it.exists() }
    })
    sourceDirectories.setFrom(provider {
        files(coveredProjects.mapNotNull {
            it.extensions.findByType(SourceSetContainer::class.java)?.findByName("main")?.allSource?.srcDirs
        })
    })
    classDirectories.setFrom(provider {
        files(coveredProjects.mapNotNull {
            it.extensions.findByType(SourceSetContainer::class.java)?.findByName("main")?.output
        }).asFileTree.matching { exclude(coverageClassExcludes) }
    })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = coverageMin
            }
        }
    }
}

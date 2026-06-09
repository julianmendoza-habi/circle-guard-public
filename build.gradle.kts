plugins {
    id("org.springframework.boot") version "3.2.4" apply false
    id("io.spring.dependency-management") version "1.1.4" apply false
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.spring") version "1.9.24" apply false
    kotlin("plugin.jpa") version "1.9.24" apply false
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

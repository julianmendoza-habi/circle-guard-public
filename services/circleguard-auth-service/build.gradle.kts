plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

/**
 * Testcontainers live under src/integrationTest so `./gradlew test` never loads those classes on CI
 * agents without Docker (avoids DockerClientProviderStrategy during default test compilation/run).
 * With `-Pintegration`, `test` finalizedBy runs `integrationTest`.
 */
sourceSets {
    val main by getting
    val test by getting
    create("integrationTest") {
        java.setSrcDirs(listOf("src/integrationTest/java"))
        // AuthRepositoryTestApplication lives in src/test; integration tests need that slice on the classpath.
        compileClasspath += main.output + test.output + configurations.testCompileClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

val integrationTestSourceSet = sourceSets.named("integrationTest").get()
configurations.named(integrationTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(integrationTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.register<Test>("integrationTest") {
    description = "Runs JDBC integration tests against PostgreSQL (Testcontainers)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.named<Test>("test") {
    if (project.hasProperty("integration")) {
        finalizedBy(tasks.named("integrationTest"))
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-ldap")
    implementation("org.springframework.security:spring-security-ldap")
    // Resilience (Circuit Breaker + Retry) on the synchronous auth -> identity hop. Not managed by
    // the Spring Boot BOM, so the version is pinned here (2.2.x targets Spring Boot 3.2). The
    // starter wires the @CircuitBreaker/@Retry aspects (needs AOP) and publishes CB metrics to the
    // shared Micrometer/Prometheus registry + an actuator health indicator.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    testImplementation("org.testcontainers:postgresql:1.19.3")
}

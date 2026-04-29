plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

/**
 * Testcontainers / full-context tests live under src/integrationTest so `./gradlew test` stays viable on CI
 * without Docker or PostgreSQL. Use `./gradlew test -Pintegration` (or finalizedBy) to run integration tests.
 */
sourceSets {
    val main by getting
    val test by getting
    create("integrationTest") {
        java.setSrcDirs(listOf("src/integrationTest/java"))
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
    description = "Integration tests (PostgreSQL, Kafka via Testcontainers)."
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
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    testImplementation("org.testcontainers:postgresql:1.19.3")
    testImplementation("org.testcontainers:kafka:1.19.3")
}

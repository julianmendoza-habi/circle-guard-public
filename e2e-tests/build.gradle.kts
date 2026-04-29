plugins {
    java
}

dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
    testImplementation("io.rest-assured:rest-assured:5.4.0")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.jsonwebtoken:jjwt-api:0.11.5")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("E2E_AUTH_URL", System.getenv("E2E_AUTH_URL") ?: "")
    systemProperty("E2E_GATEWAY_URL", System.getenv("E2E_GATEWAY_URL") ?: "")
    systemProperty("E2E_FORM_URL", System.getenv("E2E_FORM_URL") ?: "")
    systemProperty("E2E_PROMOTION_URL", System.getenv("E2E_PROMOTION_URL") ?: "")
    systemProperty("E2E_IDENTITY_URL", System.getenv("E2E_IDENTITY_URL") ?: "")
}

package com.circleguard.notification.integration;

import com.circleguard.notification.NotificationApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;

/**
 * Integration test: Kafka priority alert triggers HTTP fetch to auth-service (notification ↔ auth).
 */
@SpringBootTest(classes = NotificationApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class PriorityAlertKafkaIntegrationTest {

    private static final WireMockServer AUTH_STUB = new WireMockServer(8889);

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @BeforeAll
    static void startWireMock() {
        AUTH_STUB.start();
    }

    @AfterAll
    static void stopWireMock() {
        AUTH_STUB.stop();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("auth.api.url", () -> "http://127.0.0.1:8889");
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> "3025");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void stubAuthPermissions() {
        AUTH_STUB.resetAll();
        AUTH_STUB.stubFor(get(urlEqualTo("/api/v1/users/permissions/alert:receive_priority"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"username\":\"admin\",\"email\":\"admin@test.edu\"}]")));
    }

    @Test
    void consumesPriorityAlertAndCallsAuthPermissionsEndpoint() {
        String json = "{\"eventType\":\"CONFIRMED\",\"affectedCount\":3}";
        kafkaTemplate.send("alert.priority", "key", json);

        await().atMost(java.time.Duration.ofSeconds(25)).untilAsserted(() ->
                AUTH_STUB.verify(getRequestedFor(
                        urlEqualTo("/api/v1/users/permissions/alert:receive_priority"))));
    }
}

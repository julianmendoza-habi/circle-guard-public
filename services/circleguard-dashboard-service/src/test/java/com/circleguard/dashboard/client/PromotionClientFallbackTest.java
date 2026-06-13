package com.circleguard.dashboard.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the circuit-breaker degradation contract of {@link PromotionClient}: when the
 * resilience4j fallback fires (retries exhausted or circuit OPEN), the dashboard gets a sentinel
 * "unavailable" payload rather than an exception, keeping analytics endpoints up. The fallback
 * methods are package-private, so this exercises the degradation logic directly without needing
 * the Spring AOP proxy (the live CB/Retry wiring is validated at runtime via /actuator/circuitbreakers).
 */
class PromotionClientFallbackTest {

    private final PromotionClient client = new PromotionClient(new RestTemplateBuilder());

    @Test
    void healthStatsFallbackReturnsDegradedPayload() {
        Map<String, Object> result = client.getHealthStatsFallback(
                new ResourceAccessException("promotion-service down"));

        assertThat(result)
                .containsEntry("error", "Service unavailable")
                .containsKey("timestamp");
    }

    @Test
    void departmentStatsFallbackEchoesDepartment() {
        Map<String, Object> result = client.getHealthStatsByDepartmentFallback(
                "engineering", new ResourceAccessException("promotion-service down"));

        assertThat(result)
                .containsEntry("error", "Service unavailable")
                .containsEntry("department", "engineering");
    }
}

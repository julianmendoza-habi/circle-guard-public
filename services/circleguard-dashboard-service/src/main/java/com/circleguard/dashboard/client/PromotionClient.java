package com.circleguard.dashboard.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
@Slf4j
public class PromotionClient {

    // Resilience4j instance name; shared by the @CircuitBreaker and @Retry config in application.yml.
    static final String INSTANCE = "promotionService";

    // Built via RestTemplateBuilder so Micrometer Tracing instruments it and propagates the
    // trace context on the dashboard -> promotion hop.
    private final RestTemplate restTemplate;

    @Value("${circleguard.promotion-service.url:http://localhost:8088}")
    private String promotionServiceUrl;

    public PromotionClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    // Resilience: @Retry retries transient failures, @CircuitBreaker trips on a sustained failure
    // rate so a down promotion-service can't drag the dashboard down with it. Analytics is
    // non-critical, so the fallback degrades gracefully (returns a sentinel "unavailable" payload)
    // instead of propagating. Replaces the previous inline try/catch with declarative resilience.
    @SuppressWarnings("unchecked")
    @Retry(name = INSTANCE, fallbackMethod = "getHealthStatsFallback")
    @CircuitBreaker(name = INSTANCE)
    public Map<String, Object> getHealthStats() {
        return restTemplate.getForObject(
                promotionServiceUrl + "/api/v1/health-status/stats",
                Map.class
        );
    }

    // Package-private fallback (resilience4j invokes it reflectively; the unit test calls it directly).
    Map<String, Object> getHealthStatsFallback(Throwable t) {
        log.error("Failed to fetch health stats from promotion-service (circuit '{}'): {}", INSTANCE, t.toString());
        return Map.of("error", "Service unavailable", "timestamp", new Date());
    }

    @SuppressWarnings("unchecked")
    @Retry(name = INSTANCE, fallbackMethod = "getHealthStatsByDepartmentFallback")
    @CircuitBreaker(name = INSTANCE)
    public Map<String, Object> getHealthStatsByDepartment(String department) {
        return restTemplate.getForObject(
                promotionServiceUrl + "/api/v1/health-status/stats/department/" + department,
                Map.class
        );
    }

    Map<String, Object> getHealthStatsByDepartmentFallback(String department, Throwable t) {
        log.error("Failed to fetch department stats from promotion-service (circuit '{}'): {}", INSTANCE, t.toString());
        return Map.of("error", "Service unavailable", "department", department, "timestamp", new Date());
    }
}

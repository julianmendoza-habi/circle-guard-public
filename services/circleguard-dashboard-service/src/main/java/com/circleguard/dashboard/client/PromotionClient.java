package com.circleguard.dashboard.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
@Slf4j
public class PromotionClient {

    // Built via RestTemplateBuilder so Micrometer Tracing instruments it and propagates the
    // trace context on the dashboard -> promotion hop.
    private final RestTemplate restTemplate;

    @Value("${circleguard.promotion-service.url:http://localhost:8088}")
    private String promotionServiceUrl;

    public PromotionClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getHealthStats() {
        try {
            return restTemplate.getForObject(
                    promotionServiceUrl + "/api/v1/health-status/stats",
                    Map.class
            );
        } catch (Exception e) {
            log.error("Failed to fetch health stats from promotion-service", e);
            return Map.of("error", "Service unavailable", "timestamp", new Date());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getHealthStatsByDepartment(String department) {
        try {
            return restTemplate.getForObject(
                    promotionServiceUrl + "/api/v1/health-status/stats/department/" + department,
                    Map.class
            );
        } catch (Exception e) {
            log.error("Failed to fetch department stats from promotion-service", e);
            return Map.of("error", "Service unavailable", "department", department, "timestamp", new Date());
        }
    }
}

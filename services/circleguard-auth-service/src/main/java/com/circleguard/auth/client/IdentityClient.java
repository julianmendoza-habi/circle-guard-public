package com.circleguard.auth.client;

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
public class IdentityClient {
    // Resilience4j instance name; shared by the @CircuitBreaker and @Retry config in application.yml.
    static final String INSTANCE = "identityService";

    // Built via RestTemplateBuilder so Micrometer Tracing instruments it and propagates the
    // trace context (W3C traceparent header) on the auth -> identity hop.
    private final RestTemplate restTemplate;
    private final String identityMapUrl;

    public IdentityClient(RestTemplateBuilder restTemplateBuilder,
                          @Value("${circleguard.identity.map-url:http://localhost:8083/api/v1/identities/map}") String identityMapUrl) {
        this.restTemplate = restTemplateBuilder.build();
        this.identityMapUrl = identityMapUrl;
    }

    // Resilience: @Retry (outermost aspect by default) absorbs transient blips with backoff, while
    // @CircuitBreaker trips after a sustained failure rate so we stop hammering a dead
    // identity-service. The fallback lives on @Retry so it only fires once retries are exhausted or
    // the circuit is OPEN (CallNotPermittedException is not in the retry whitelist -> straight to
    // fallback). CB state is exported as a metric + actuator health indicator (see application.yml).
    @Retry(name = INSTANCE, fallbackMethod = "getAnonymousIdFallback")
    @CircuitBreaker(name = INSTANCE)
    public UUID getAnonymousId(String realIdentity) {
        Map<String, String> request = Map.of("realIdentity", realIdentity);
        Map response = restTemplate.postForObject(identityMapUrl, request, Map.class);
        return UUID.fromString(response.get("anonymousId").toString());
    }

    // Package-private so the unit test can assert the degradation contract directly (resilience4j
    // invokes it reflectively, so visibility beyond the package is not required).
    UUID getAnonymousIdFallback(String realIdentity, Throwable t) {
        log.error("identity-service unavailable (circuit '{}'): {}", INSTANCE, t.toString());
        throw new IdentityServiceUnavailableException(
                "Could not resolve anonymous identity; identity-service is unavailable", t);
    }
}

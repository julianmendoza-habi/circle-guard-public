package com.circleguard.auth.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Component
public class IdentityClient {
    // Built via RestTemplateBuilder so Micrometer Tracing instruments it and propagates the
    // trace context (W3C traceparent header) on the auth -> identity hop.
    private final RestTemplate restTemplate;
    private final String identityMapUrl;

    public IdentityClient(RestTemplateBuilder restTemplateBuilder,
                          @Value("${circleguard.identity.map-url:http://localhost:8083/api/v1/identities/map}") String identityMapUrl) {
        this.restTemplate = restTemplateBuilder.build();
        this.identityMapUrl = identityMapUrl;
    }

    public UUID getAnonymousId(String realIdentity) {
        Map<String, String> request = Map.of("realIdentity", realIdentity);
        Map response = restTemplate.postForObject(identityMapUrl, request, Map.class);
        return UUID.fromString(response.get("anonymousId").toString());
    }
}

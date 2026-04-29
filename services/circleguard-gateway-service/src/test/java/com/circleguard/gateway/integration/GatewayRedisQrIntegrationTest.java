package com.circleguard.gateway.integration;

import com.circleguard.gateway.GatewayServiceApplication;
import com.circleguard.gateway.service.QrValidationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.security.Key;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: QR validation reads campus access status from Redis (gateway ↔ Redis).
 */
@SpringBootTest(classes = GatewayServiceApplication.class)
@Testcontainers
@Tag("integration")
class GatewayRedisQrIntegrationTest {

    private static final String QR_SECRET = "my-qr-secret-key-for-dev-1234567890";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("jwt.secret", () -> "my-super-secret-dev-key-32-chars-long-12345678");
        registry.add("jwt.expiration", () -> "3600000");
        registry.add("qr.secret", () -> QR_SECRET);
    }

    @Autowired
    private QrValidationService qrValidationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void redisBackedStatus_blocksCampusAccessWhenContagiousFlagPresent() {
        UUID anonymousId = UUID.randomUUID();
        Key key = Keys.hmacShaKeyFor(QR_SECRET.getBytes());
        String token = Jwts.builder()
                .setSubject(anonymousId.toString())
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        redisTemplate.opsForValue().set("user:status:" + anonymousId, "CONTAGIED");

        QrValidationService.ValidationResult result = qrValidationService.validateToken(token);

        assertFalse(result.valid());
    }

    @Test
    void redisBackedStatus_allowsWhenNoRiskFlag() {
        UUID anonymousId = UUID.randomUUID();
        Key key = Keys.hmacShaKeyFor(QR_SECRET.getBytes());
        String token = Jwts.builder()
                .setSubject(anonymousId.toString())
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        redisTemplate.opsForValue().set("user:status:" + anonymousId, "CLEAR");

        QrValidationService.ValidationResult result = qrValidationService.validateToken(token);

        assertTrue(result.valid());
    }
}

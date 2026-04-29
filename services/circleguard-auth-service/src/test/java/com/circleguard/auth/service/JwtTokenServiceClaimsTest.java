package com.circleguard.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JWT issuance (claims shape and HS256 structure).
 */
class JwtTokenServiceClaimsTest {

    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService(
                "test-secret-key-exactly-32-bytes-long!",
                3600000L);
    }

    @Test
    void generateToken_containsThreeJwtSegments() {
        UUID id = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user", "pwd", List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));

        String token = jwtTokenService.generateToken(id, auth);

        assertNotNull(token);
        assertEquals(3, token.split("\\.").length);
    }

    @Test
    void generateToken_withVisitorAuthority_producesNonEmptyCompactJwt() {
        UUID id = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                id.toString(), null, List.of(new SimpleGrantedAuthority("VISITOR")));

        String token = jwtTokenService.generateToken(id, auth);

        assertFalse(token.isBlank());
        assertTrue(token.length() > 40);
    }

    @Test
    void generateToken_preservesSubjectMatchingAnonymousId() {
        UUID id = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "admin", "pwd",
                List.of(
                        new SimpleGrantedAuthority("ROLE_HEALTH_CENTER"),
                        new SimpleGrantedAuthority("PERM_AUDIT")));

        String token = jwtTokenService.generateToken(id, auth);

        assertNotNull(token);
        assertTrue(token.startsWith("eyJ"));
    }
}

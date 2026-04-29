package com.circleguard.e2e;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.security.Key;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End-to-end HTTP flows against deployed services (set env URLs + E2E_RUN=true).
 */
@EnabledIfEnvironmentVariable(named = "E2E_RUN", matches = "true")
class CircleGuardEndToEndIT {

    private static String authUrl;
    private static String gatewayUrl;
    private static String formUrl;
    private static String promotionUrl;
    private static String identityUrl;

    @BeforeAll
    static void configure() {
        authUrl = trimSlash(System.getenv("E2E_AUTH_URL"));
        gatewayUrl = trimSlash(System.getenv("E2E_GATEWAY_URL"));
        formUrl = trimSlash(System.getenv("E2E_FORM_URL"));
        promotionUrl = trimSlash(System.getenv("E2E_PROMOTION_URL"));
        identityUrl = trimSlash(System.getenv("E2E_IDENTITY_URL"));
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    private static String trimSlash(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Test
    void visitorHandoff_returnsBearerToken() {
        UUID anon = UUID.randomUUID();
        String token = given()
                .contentType(ContentType.JSON)
                .body(Map.of("anonymousId", anon.toString()))
                .post(authUrl + "/api/v1/auth/visitor/handoff")
                .then()
                .statusCode(200)
                .body("token", notNullValue())
                .extract()
                .path("token");
        assertThat(token).isNotBlank();
    }

    @Test
    void gateValidate_acceptsQrSignedCampusToken() {
        String qrSecret = System.getenv().getOrDefault("E2E_QR_SECRET", "my-qr-secret-key-for-dev-1234567890");
        UUID anonymousId = UUID.randomUUID();
        Key key = Keys.hmacShaKeyFor(qrSecret.getBytes());
        String qrToken = Jwts.builder()
                .setSubject(anonymousId.toString())
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("token", qrToken))
                .post(gatewayUrl + "/api/v1/gate/validate")
                .then()
                .statusCode(200)
                .body("valid", notNullValue());
    }

    @Test
    void activeQuestionnaire_isReachable() {
        given()
                .get(formUrl + "/api/v1/questionnaires/active")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(404)));
    }

    @Test
    void identityMap_returnsAnonymousId() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("realIdentity", "e2e-user-" + UUID.randomUUID()))
                .post(identityUrl + "/api/v1/identities/map")
                .then()
                .statusCode(200)
                .body("anonymousId", notNullValue());
    }

    @Test
    void promotionBuildings_catalogIsReadable() {
        given()
                .get(promotionUrl + "/api/v1/buildings")
                .then()
                .statusCode(200);
    }
}

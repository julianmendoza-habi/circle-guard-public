package com.circleguard.auth.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the circuit-breaker degradation contract of {@link IdentityClient}. Anonymous-id
 * resolution is mandatory for issuing a token, so when the fallback fires it must fail fast with a
 * typed {@link IdentityServiceUnavailableException} (mapped to HTTP 500 by LoginController) rather
 * than return a bogus id. The fallback is package-private; the live CB/Retry aspect wiring is
 * validated at runtime via /actuator/circuitbreakers.
 */
class IdentityClientFallbackTest {

    private final IdentityClient client =
            new IdentityClient(new RestTemplateBuilder(), "http://localhost:8083/api/v1/identities/map");

    @Test
    void fallbackFailsFastWithTypedException() {
        Throwable cause = new ResourceAccessException("identity-service down");

        assertThatThrownBy(() -> client.getAnonymousIdFallback("alice@uni.edu", cause))
                .isInstanceOf(IdentityServiceUnavailableException.class)
                .hasCause(cause);
    }
}

package com.circleguard.auth.client;

/**
 * Raised by {@link IdentityClient}'s circuit-breaker fallback when identity-service cannot be
 * reached (retries exhausted or circuit OPEN). The auth flow treats anonymous-id resolution as
 * mandatory, so the fallback fails fast with this typed exception instead of fabricating an id.
 * {@code LoginController} maps it to HTTP 500 via its catch-all handler.
 */
public class IdentityServiceUnavailableException extends RuntimeException {
    public IdentityServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.samvaad.tui.session;

import com.samvaad.tui.api.dto.AuthResponse;
import java.time.Instant;

/**
 * Authenticated session material, held in memory only.
 *
 * <p>Tokens come exclusively from server authentication/refresh responses
 * and are never logged, printed, or persisted.
 */
public record AuthSession(
        String accessToken,
        String refreshToken,
        String sessionId,
        long expiresInSeconds,
        Instant acquiredAt) {

    public AuthSession {
        if (isBlank(accessToken) || isBlank(refreshToken) || isBlank(sessionId)) {
            throw new IllegalArgumentException("Auth tokens and session id must not be empty.");
        }
        if (expiresInSeconds < 0) {
            throw new IllegalArgumentException("Expiry must not be negative.");
        }
        if (acquiredAt == null) {
            throw new IllegalArgumentException("Acquisition time must not be null.");
        }
    }

    public static AuthSession from(AuthResponse response) {
        return new AuthSession(response.accessToken(), response.refreshToken(),
                response.sessionId(), response.expiresIn(), Instant.now());
    }

    public Instant expiresAt() {
        return acquiredAt.plusSeconds(expiresInSeconds);
    }

    public boolean isExpired() {
        return !Instant.now().isBefore(expiresAt());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

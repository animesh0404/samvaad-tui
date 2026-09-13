package com.samvaad.tui.session;

import com.samvaad.tui.api.dto.AuthResponse;
import com.samvaad.tui.auth.JwtSubject;
import com.samvaad.tui.config.AppConfig;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated session material, held in memory only.
 *
 * <p>Tokens come exclusively from server authentication/refresh responses
 * and are never logged, printed, or persisted. The user id is the JWT
 * {@code sub} claim decoded from the access token for sender attribution.
 */
public record AuthSession(
        String accessToken,
        String refreshToken,
        String sessionId,
        long expiresInSeconds,
        Instant acquiredAt,
        UUID userId) {

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
        if (userId == null) {
            throw new IllegalArgumentException("User id must not be null.");
        }
    }

    public static AuthSession from(AuthResponse response) {
        return new AuthSession(response.accessToken(), response.refreshToken(),
                response.sessionId(), response.expiresIn(), Instant.now(),
                JwtSubject.subject(response.accessToken()));
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

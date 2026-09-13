package com.samvaad.tui.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.AuthResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthSessionTest {

    @Test
    void fromMapsServerResponse() {
        AuthSession session = AuthSession.from(
                new AuthResponse("access-1", "refresh-1", 3600, "sid-1"));

        assertEquals("access-1", session.accessToken());
        assertEquals("refresh-1", session.refreshToken());
        assertEquals("sid-1", session.sessionId());
        assertEquals(3600, session.expiresInSeconds());
    }

    @Test
    void expiryIsComputedFromAcquisition() {
        Instant acquired = Instant.parse("2026-09-13T00:00:00Z");
        AuthSession session = new AuthSession("a", "r", "s", 60, acquired);

        assertEquals(acquired.plusSeconds(60), session.expiresAt());
        assertTrue(session.isExpired(), "a 2026 session with 60s TTL is long expired");
    }

    @Test
    void freshSessionIsNotExpired() {
        AuthSession session = new AuthSession("a", "r", "s", 3600, Instant.now());

        assertFalse(session.isExpired());
    }

    @Test
    void rejectsBlankTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSession("", "r", "s", 60, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSession("a", " ", "s", 60, Instant.now()));
    }
}

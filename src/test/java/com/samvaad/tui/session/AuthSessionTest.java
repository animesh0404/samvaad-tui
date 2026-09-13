package com.samvaad.tui.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.AuthResponse;
import com.samvaad.tui.auth.TestTokens;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthSessionTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void fromMapsServerResponse() {
        AuthSession session = AuthSession.from(new AuthResponse(
                TestTokens.accessTokenFor(USER_ID), "refresh-1", 3600, "sid-1"));

        assertEquals("refresh-1", session.refreshToken());
        assertEquals("sid-1", session.sessionId());
        assertEquals(3600, session.expiresInSeconds());
        assertEquals(USER_ID, session.userId());
    }

    @Test
    void fromRejectsUnreadableSubject() {
        assertThrows(IllegalArgumentException.class, () -> AuthSession.from(
                new AuthResponse("not-a-jwt", "refresh-1", 3600, "sid-1")));
    }

    @Test
    void expiryIsComputedFromAcquisition() {
        Instant acquired = Instant.parse("2026-09-13T00:00:00Z");
        AuthSession session = new AuthSession("a", "r", "s", 60, acquired, USER_ID);

        assertEquals(acquired.plusSeconds(60), session.expiresAt());
        assertTrue(session.isExpired(), "a 2026 session with 60s TTL is long expired");
    }

    @Test
    void freshSessionIsNotExpired() {
        AuthSession session = new AuthSession("a", "r", "s", 3600, Instant.now(), USER_ID);

        assertFalse(session.isExpired());
    }

    @Test
    void rejectsBlankTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSession("", "r", "s", 60, Instant.now(), USER_ID));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSession("a", " ", "s", 60, Instant.now(), USER_ID));
    }

    @Test
    void rejectsNullUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSession("a", "r", "s", 60, Instant.now(), null));
    }
}

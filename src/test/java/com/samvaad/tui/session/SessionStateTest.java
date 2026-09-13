package com.samvaad.tui.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.AuthResponse;
import com.samvaad.tui.config.AppConfig;
import org.junit.jupiter.api.Test;

class SessionStateTest {

    private static final AppConfig CONFIG = new AppConfig("http://localhost:8080", "alice");

    private static AuthSession session(String access, String refresh) {
        return new AuthSession(access, refresh, "sid-1", 3600,
                java.time.Instant.parse("2026-09-13T00:00:00Z"));
    }

    @Test
    void startsUnauthenticated() {
        SessionState session = SessionState.unauthenticated(CONFIG);
        assertEquals(SessionState.AuthStatus.UNAUTHENTICATED, session.authStatus());
        assertEquals("http://localhost:8080", session.config().serverUrl());
        assertEquals("alice", session.config().username());
        assertTrue(session.authSession().isEmpty());
    }

    @Test
    void authenticatedHoldsTokens() {
        SessionState session = SessionState.authenticated(CONFIG, session("access-1", "refresh-1"));

        assertEquals(SessionState.AuthStatus.AUTHENTICATED, session.authStatus());
        assertEquals("access-1", session.authSession().orElseThrow().accessToken());
        assertEquals("refresh-1", session.authSession().orElseThrow().refreshToken());
    }

    @Test
    void refreshedTokensReplacePreviousPair() {
        SessionState session = SessionState.authenticated(CONFIG, session("access-1", "refresh-1"));

        SessionState refreshed = session.withRefreshedTokens(
                new AuthResponse("access-2", "refresh-2", 1800, "sid-1"));

        assertEquals(SessionState.AuthStatus.AUTHENTICATED, refreshed.authStatus());
        assertEquals("access-2", refreshed.authSession().orElseThrow().accessToken());
        assertEquals("refresh-2", refreshed.authSession().orElseThrow().refreshToken());
        assertEquals("access-1", session.authSession().orElseThrow().accessToken(),
                "original state must be unchanged");
    }

    @Test
    void refreshOnUnauthenticatedFails() {
        SessionState session = SessionState.unauthenticated(CONFIG);

        assertThrows(IllegalStateException.class, () -> session.withRefreshedTokens(
                new AuthResponse("access-2", "refresh-2", 1800, "sid-1")));
    }

    @Test
    void clearedDropsToUnauthenticated() {
        SessionState session = SessionState.authenticated(CONFIG, session("access-1", "refresh-1"));

        SessionState cleared = session.cleared();

        assertEquals(SessionState.AuthStatus.UNAUTHENTICATED, cleared.authStatus());
        assertTrue(cleared.authSession().isEmpty());
    }
}

package com.samvaad.tui.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.samvaad.tui.config.AppConfig;
import org.junit.jupiter.api.Test;

class SessionStateTest {

    @Test
    void startsUnauthenticated() {
        SessionState session = SessionState.unauthenticated(
                new AppConfig("http://localhost:8080", "alice"));
        assertEquals(SessionState.AuthStatus.UNAUTHENTICATED, session.authStatus());
        assertEquals("http://localhost:8080", session.config().serverUrl());
        assertEquals("alice", session.config().username());
    }
}

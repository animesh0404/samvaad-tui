package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.SamvaadApiException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiAppLogoutTest {

    private static TuiSession session(LogoutService logout) {
        return new TuiSession("alice", "http://localhost:8080", null, null, null, null, null,
                null, null, null, null, null, logout);
    }

    @Test
    void successfulLogoutReturnsTrueAndConfirms() {
        List<String> calls = new ArrayList<>();
        TuiState state = new TuiState();

        assertTrue(TuiApp.performLogout(session(() -> calls.add("logout")), state));
        assertEquals(List.of("logout"), calls);
        assertEquals("Logged out.", state.status());
    }

    @Test
    void failedLogoutReturnsFalseAndKeepsCredential() {
        TuiState state = new TuiState();
        LogoutService failing = () -> {
            throw new SamvaadApiException(
                    SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1, "down");
        };

        assertFalse(TuiApp.performLogout(session(failing), state));
        assertTrue(state.status().contains("Logout failed"),
                "failure must stay in the TUI with an explicit status");
    }

    @Test
    void nullLogoutIsUnavailable() {
        TuiState state = new TuiState();

        assertFalse(TuiApp.performLogout(session(null), state));
        assertEquals("Logout unavailable.", state.status());
    }
}

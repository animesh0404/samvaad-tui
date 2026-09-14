package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TuiStateTest {

    @Test
    void selectionClampsToConversationCount() {
        TuiState state = new TuiState();

        state.selectDown(3);
        state.selectDown(3);
        state.selectDown(3);
        assertEquals(2, state.selectedIndex());

        state.selectUp(3);
        state.selectUp(3);
        state.selectUp(3);
        assertEquals(0, state.selectedIndex());
    }

    @Test
    void selectionIgnoresEmptyList() {
        TuiState state = new TuiState();

        state.selectDown(0);
        state.selectUp(0);

        assertEquals(0, state.selectedIndex());
    }

    @Test
    void focusTogglesBetweenListAndComposer() {
        TuiState state = new TuiState();
        assertEquals(TuiState.Focus.CONVERSATIONS, state.focus());

        state.toggleFocus();
        assertEquals(TuiState.Focus.COMPOSER, state.focus());

        state.toggleFocus();
        assertEquals(TuiState.Focus.CONVERSATIONS, state.focus());
    }

    @Test
    void composerEditsAndClears() {
        TuiState state = new TuiState();

        state.appendToComposer('h');
        state.appendToComposer('i');
        assertEquals("hi", state.composer());

        state.backspaceComposer();
        assertEquals("h", state.composer());

        state.clearComposer();
        assertEquals("", state.composer());

        state.backspaceComposer();
        assertEquals("", state.composer());
    }

    @Test
    void pendingSendTracksReconciliationKey() {
        TuiState state = new TuiState();
        assertNull(state.pendingSend());

        UUID requestId = UUID.randomUUID();
        state.setPendingSend(requestId);
        assertEquals(requestId, state.pendingSend());

        state.clearPendingSend();
        assertNull(state.pendingSend());
    }

    @Test
    void helpAndStatusFlags() {        TuiState state = new TuiState();
        assertFalse(state.helpVisible());

        state.toggleHelp();
        assertTrue(state.helpVisible());

        state.closeHelp();
        assertFalse(state.helpVisible());

        state.setStatus("hello");
        assertEquals("hello", state.status());
    }
}

package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.samvaad.tui.model.ConversationEntry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TuiControllerTest {

    private final TuiController controller = new TuiController();

    private static ConversationEntry entry(String id, String username) {
        return new ConversationEntry(UUID.fromString(id), UUID.randomUUID(), username, 1,
                LocalDateTime.of(2026, 9, 14, 10, 0));
    }

    private final List<ConversationEntry> conversations = List.of(
            entry("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "alice"),
            entry("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "bob"),
            entry("cccccccc-cccc-cccc-cccc-cccccccccccc", "charlie"));

    private static KeyStroke key(KeyType type) {
        return new KeyStroke(type);
    }

    private static KeyStroke ch(char c) {
        return new KeyStroke(c, false, false);
    }

    @Test
    void arrowsAndVimKeysMoveSelection() {
        TuiState state = new TuiState();

        controller.handle(key(KeyType.ArrowDown), state, conversations);
        controller.handle(ch('j'), state, conversations);
        assertEquals(2, state.selectedIndex());

        controller.handle(key(KeyType.ArrowUp), state, conversations);
        controller.handle(ch('k'), state, conversations);
        assertEquals(0, state.selectedIndex());
    }

    @Test
    void tabTogglesFocus() {
        TuiState state = new TuiState();

        controller.handle(key(KeyType.Tab), state, conversations);
        assertEquals(TuiState.Focus.COMPOSER, state.focus());

        controller.handle(key(KeyType.ReverseTab), state, conversations);
        assertEquals(TuiState.Focus.CONVERSATIONS, state.focus());
    }

    @Test
    void typingEditsComposerOnlyWhenFocused() {
        TuiState state = new TuiState();

        controller.handle(ch('x'), state, conversations);
        assertEquals("", state.composer(), "typing in the list must not edit the composer");

        state.toggleFocus();
        controller.handle(ch('h'), state, conversations);
        controller.handle(ch('i'), state, conversations);
        assertEquals("hi", state.composer());

        controller.handle(key(KeyType.Backspace), state, conversations);
        assertEquals("h", state.composer());
    }

    @Test
    void questionMarkOpensHelpFromListButTypesInComposer() {
        TuiState listState = new TuiState();
        controller.handle(ch('?'), listState, conversations);
        assertTrue(listState.helpVisible());

        TuiState composerState = new TuiState();
        composerState.toggleFocus();
        controller.handle(ch('?'), composerState, conversations);
        assertEquals("?", composerState.composer());
    }

    @Test
    void escapeClosesHelp() {
        TuiState state = new TuiState();
        controller.handle(key(KeyType.F1), state, conversations);
        assertTrue(state.helpVisible());

        controller.handle(key(KeyType.Escape), state, conversations);
        assertTrue(!state.helpVisible());
    }

    @Test
    void anyOtherKeyDismissesHelpAndActsNormally() {
        TuiState tabState = new TuiState();
        tabState.toggleHelp();
        controller.handle(key(KeyType.Tab), tabState, conversations);
        assertTrue(!tabState.helpVisible(), "help must close");
        assertEquals(TuiState.Focus.COMPOSER, tabState.focus(), "Tab must still toggle focus");

        TuiState arrowState = new TuiState();
        arrowState.toggleHelp();
        controller.handle(key(KeyType.ArrowDown), arrowState, conversations);
        assertTrue(!arrowState.helpVisible(), "help must close");
        assertEquals(1, arrowState.selectedIndex(), "arrow must still move selection");

        TuiState quitState = new TuiState();
        quitState.toggleHelp();
        assertEquals(TuiController.Action.QUIT,
                controller.handle(ch('q'), quitState, conversations),
                "q with help open must quit, not swallow");
        assertTrue(!quitState.helpVisible());
    }

    @Test
    void f10QuitsWithHelpOpen() {
        TuiState state = new TuiState();
        state.toggleHelp();
        assertEquals(TuiController.Action.QUIT,
                controller.handle(key(KeyType.F10), state, conversations));
        assertTrue(!state.helpVisible());
    }

    @Test
    void mergedAltKeysAreIgnoredButNeverStrandHelpOpen() {
        // Rapid Esc+X bursts arrive from Lanterna as a single Alt-modified
        // keystroke (verified against the decoder). They carry no binding,
        // but with help open they must still dismiss the overlay.
        TuiState helpState = new TuiState();
        helpState.toggleHelp();
        assertEquals(TuiController.Action.CONTINUE,
                controller.handle(new KeyStroke('\t', false, true), helpState, conversations));
        assertTrue(!helpState.helpVisible(), "merged Esc+Tab must still dismiss help");
        assertEquals(TuiState.Focus.CONVERSATIONS, helpState.focus(),
                "merged key itself must not act");

        TuiState listState = new TuiState();
        assertEquals(TuiController.Action.CONTINUE,
                controller.handle(new KeyStroke('j', false, true), listState, conversations));
        assertEquals(0, listState.selectedIndex(), "Alt+j must not move selection");
    }

    @Test
    void quitKeys() {
        assertEquals(TuiController.Action.QUIT,
                controller.handle(key(KeyType.F10), new TuiState(), conversations));
        assertEquals(TuiController.Action.QUIT,
                controller.handle(ch('q'), new TuiState(), conversations));
        assertEquals(TuiController.Action.QUIT,
                controller.handle(new KeyStroke('c', true, false), new TuiState(), conversations));

        TuiState composerState = new TuiState();
        composerState.toggleFocus();
        assertEquals(TuiController.Action.CONTINUE,
                controller.handle(ch('q'), composerState, conversations),
                "q in the composer must type, not quit");
        assertEquals("q", composerState.composer());
    }

    @Test
    void enterOpensConversationOrRequestsSend() {
        TuiState listState = new TuiState();
        controller.handle(key(KeyType.Enter), listState, conversations);
        assertTrue(listState.status().contains("alice"));

        TuiState composerState = new TuiState();
        composerState.toggleFocus();
        composerState.appendToComposer('h');
        assertEquals(TuiController.Action.SEND,
                controller.handle(key(KeyType.Enter), composerState, conversations));
        assertEquals("h", composerState.composer(),
                "composer text stays until the send is handed over");
    }

    @Test
    void blankComposerEnterHintsInsteadOfSending() {
        TuiState composerState = new TuiState();
        composerState.toggleFocus();

        assertEquals(TuiController.Action.CONTINUE,
                controller.handle(key(KeyType.Enter), composerState, conversations));
        assertTrue(composerState.status().contains("Type a message"));
    }

    @Test
    void nullKeyIsIgnored() {
        assertEquals(TuiController.Action.CONTINUE,
                controller.handle(null, new TuiState(), conversations));
    }
}

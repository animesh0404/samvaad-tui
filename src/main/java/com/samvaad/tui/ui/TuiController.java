package com.samvaad.tui.ui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.List;

/**
 * Maps Lanterna key strokes onto {@link TuiState} transitions.
 *
 * <p>Bindings: Up/Down or k/j select, Tab moves focus, Enter opens the
 * highlighted conversation or (in the composer) shows the Phase 5 notice,
 * F1 or '?' toggles help, Esc closes help, F10 / Ctrl+C / 'q' in the list
 * quits. Typing otherwise edits the composer when it is focused.
 */
public final class TuiController {

    public enum Action {
        CONTINUE,
        QUIT
    }

    public Action handle(KeyStroke key, TuiState state, List<ConversationView> conversations) {
        if (key == null) {
            return Action.CONTINUE;
        }
        if (state.helpVisible()) {
            if (key.getKeyType() == KeyType.Escape
                    || key.getKeyType() == KeyType.F1
                    || isCharacter(key, '?')
                    || key.getKeyType() == KeyType.F10) {
                state.closeHelp();
                if (key.getKeyType() == KeyType.F10) {
                    return Action.QUIT;
                }
                return Action.CONTINUE;
            }
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.F10 || isCtrlC(key)) {
            return Action.QUIT;
        }
        if (key.getKeyType() == KeyType.F1) {
            state.toggleHelp();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Tab || key.getKeyType() == KeyType.ReverseTab) {
            state.toggleFocus();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Escape) {
            state.focusConversations();
            return Action.CONTINUE;
        }
        if (state.focus() == TuiState.Focus.CONVERSATIONS) {
            return handleConversationKeys(key, state, conversations);
        }
        return handleComposerKeys(key, state);
    }

    private Action handleConversationKeys(KeyStroke key, TuiState state, List<ConversationView> conversations) {
        int count = conversations.size();
        if (key.getKeyType() == KeyType.ArrowUp || isCharacter(key, 'k')) {
            state.selectUp(count);
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.ArrowDown || isCharacter(key, 'j')) {
            state.selectDown(count);
            return Action.CONTINUE;
        }
        if (isCharacter(key, '?')) {
            state.toggleHelp();
            return Action.CONTINUE;
        }
        if (isCharacter(key, 'q')) {
            return Action.QUIT;
        }
        if (key.getKeyType() == KeyType.Enter && !conversations.isEmpty()) {
            int index = Math.min(state.selectedIndex(), count - 1);
            state.setStatus("Opened " + conversations.get(index).title() + " (preview).");
            return Action.CONTINUE;
        }
        return Action.CONTINUE;
    }

    private Action handleComposerKeys(KeyStroke key, TuiState state) {
        if (key.getKeyType() == KeyType.Enter) {
            state.clearComposer();
            state.setStatus("Message sending arrives in Phase 5 - preview shell only.");
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Backspace) {
            state.backspaceComposer();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Character && !key.isCtrlDown() && !key.isAltDown()) {
            Character c = key.getCharacter();
            if (c != null && !Character.isISOControl(c)) {
                state.appendToComposer(c);
            }
            return Action.CONTINUE;
        }
        return Action.CONTINUE;
    }

    private static boolean isCharacter(KeyStroke key, char c) {
        return key.getKeyType() == KeyType.Character
                && !key.isCtrlDown()
                && !key.isAltDown()
                && key.getCharacter() != null
                && (key.getCharacter() == c || key.getCharacter() == Character.toUpperCase(c));
    }

    private static boolean isCtrlC(KeyStroke key) {
        if (key.getKeyType() != KeyType.Character || !key.isCtrlDown() || key.getCharacter() == null) {
            return false;
        }
        char c = key.getCharacter();
        return c == 'c' || c == 'C' || c == 3;
    }
}

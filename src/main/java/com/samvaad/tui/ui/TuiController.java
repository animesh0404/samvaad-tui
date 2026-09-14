package com.samvaad.tui.ui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.samvaad.tui.model.ConversationEntry;
import java.util.List;

/**
 * Maps Lanterna key strokes onto {@link TuiState} transitions.
 *
 * <p>Bindings: Up/Down or k/j select, Tab moves focus, Enter opens the
 * highlighted conversation, Enter in the composer requests a send,
 * F1 or '?' toggles help, Esc closes help, any other key dismisses the
 * help overlay and is processed normally, F10 / Ctrl+C / 'q' in the list
 * quits. Typing otherwise edits the composer when it is focused.
 */
public final class TuiController {

    public enum Action {
        CONTINUE,
        QUIT,
        SEND
    }

    public Action handle(KeyStroke key, TuiState state, List<ConversationEntry> conversations) {
        if (key == null) {
            return Action.CONTINUE;
        }
        if (state.helpVisible()) {
            if (key.getKeyType() == KeyType.F10) {
                state.closeHelp();
                return Action.QUIT;
            }
            if (key.getKeyType() == KeyType.Escape
                    || key.getKeyType() == KeyType.F1
                    || isCharacter(key, '?')) {
                state.closeHelp();
                return Action.CONTINUE;
            }
            // Any other key dismisses the overlay and is then processed
            // normally, so no input is ever swallowed while help is open.
            state.closeHelp();
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

    private Action handleConversationKeys(KeyStroke key, TuiState state, List<ConversationEntry> conversations) {
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
            state.setStatus("Opened " + conversations.get(index).displayName() + ".");
            return Action.CONTINUE;
        }
        return Action.CONTINUE;
    }

    private Action handleComposerKeys(KeyStroke key, TuiState state) {
        if (key.getKeyType() == KeyType.Enter) {
            if (state.composer().isBlank()) {
                state.setStatus("Type a message first.");
                return Action.CONTINUE;
            }
            return Action.SEND;
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

package com.samvaad.tui.ui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.samvaad.tui.model.ConversationEntry;
import java.util.List;

/**
 * Maps Lanterna key strokes onto {@link TuiState} transitions.
 *
 * <p>Chat bindings (Phase 5, unchanged): Up/Down or k/j select, Tab
 * moves focus, Enter opens the highlighted conversation, Enter in the
 * composer requests a send, F1 or '?' toggles help, Esc closes help,
 * any other key dismisses the help overlay and is processed normally,
 * F10 / Ctrl+C / 'q' in the list quits. Typing otherwise edits the
 * composer when it is focused.
 *
 * <p>Search/request bindings: '/' opens exact-username search, 'r'
 * opens the pending friend-request lists (both from the conversation
 * list). In SEARCH, typing edits the username, Enter looks it up, Tab
 * moves to the send action and Enter sends the friend request, Esc
 * returns to chat. In REQUESTS, Tab switches incoming/outgoing,
 * Up/Down or k/j select, 'a' accepts, 'x' rejects, 'c' cancels,
 * Enter runs the primary action (accept/cancel), 'g' refreshes, and
 * Esc or 'q' returns to chat.
 *
 * <p>Friends tab: Left/Right switches the CONVERSATIONS/FRIENDS tabs
 * while the sidebar is focused. In FRIENDS, Up/Down or k/j select,
 * Enter opens the selected friend's chat, 'g' refreshes the list,
 * Esc returns focus per existing conventions.
 *
 * <p>F5 is the universal manual refresh: it refreshes the authoritative
 * data for the active view (conversations, friends, search lookup, or
 * the displayed request list) through that view's existing mechanism.
 */
public final class TuiController {

    public enum Action {
        CONTINUE,
        QUIT,
        SEND,
        LOOKUP_USER,
        SEND_FRIEND_REQUEST,
        ACCEPT_REQUEST,
        REJECT_REQUEST,
        CANCEL_REQUEST,
        REFRESH_REQUESTS,
        REFRESH_FRIENDS,
        REFRESH_CONVERSATIONS,
        SELECT_FRIEND,
        SEND_FIRST_MESSAGE
    }

    public Action handle(KeyStroke key, TuiState state, List<ConversationEntry> conversations) {
        return handle(key, state, conversations, 0, 0);
    }

    public Action handle(KeyStroke key, TuiState state, List<ConversationEntry> conversations,
            int incomingCount, int outgoingCount) {
        return handle(key, state, conversations, incomingCount, outgoingCount, 0);
    }

    public Action handle(KeyStroke key, TuiState state, List<ConversationEntry> conversations,
            int incomingCount, int outgoingCount, int friendCount) {
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
        if (key.getKeyType() == KeyType.F5) {
            return refreshActiveView(state);
        }
        if (state.view() == TuiState.View.SEARCH) {
            return handleSearchKeys(key, state);
        }
        if (state.view() == TuiState.View.REQUESTS) {
            return handleRequestKeys(key, state, incomingCount, outgoingCount);
        }
        if (key.getKeyType() == KeyType.Tab || key.getKeyType() == KeyType.ReverseTab) {
            state.toggleFocus();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Escape) {
            if (state.pendingNewChat() != null) {
                state.clearNewChat();
            }
            state.focusConversations();
            return Action.CONTINUE;
        }
        if (state.focus() == TuiState.Focus.CONVERSATIONS) {
            return handleConversationKeys(key, state, conversations, friendCount);
        }
        return handleComposerKeys(key, state);
    }

    /**
     * Universal manual refresh: refreshes the authoritative data for
     * the currently active server-backed view through that view's
     * existing refresh mechanism. Never restarts the TUI.
     */
    private Action refreshActiveView(TuiState state) {
        if (state.view() == TuiState.View.SEARCH) {
            return Action.LOOKUP_USER;
        }
        if (state.view() == TuiState.View.REQUESTS) {
            return Action.REFRESH_REQUESTS;
        }
        if (state.sidebarTab() == TuiState.SidebarTab.FRIENDS) {
            return Action.REFRESH_FRIENDS;
        }
        return Action.REFRESH_CONVERSATIONS;
    }

    private Action handleConversationKeys(KeyStroke key, TuiState state,
            List<ConversationEntry> conversations, int friendCount) {
        if (key.getKeyType() == KeyType.ArrowLeft) {
            state.selectPreviousTab();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.ArrowRight) {
            state.selectNextTab();
            if (state.sidebarTab() == TuiState.SidebarTab.FRIENDS) {
                return Action.REFRESH_FRIENDS;
            }
            return Action.CONTINUE;
        }
        if (state.sidebarTab() == TuiState.SidebarTab.FRIENDS) {
            return handleFriendKeys(key, state, friendCount);
        }
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
        if (isCharacter(key, '/')) {
            state.enterSearch();
            state.setStatus("Search user by exact username.");
            return Action.CONTINUE;
        }
        if (isCharacter(key, 'r')) {
            state.enterRequests();
            state.setStatus("Friend requests.");
            return Action.REFRESH_REQUESTS;
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

    private Action handleFriendKeys(KeyStroke key, TuiState state, int friendCount) {
        if (key.getKeyType() == KeyType.ArrowUp || isCharacter(key, 'k')) {
            state.selectFriendUp(friendCount);
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.ArrowDown || isCharacter(key, 'j')) {
            state.selectFriendDown(friendCount);
            return Action.CONTINUE;
        }
        if (isCharacter(key, '?')) {
            state.toggleHelp();
            return Action.CONTINUE;
        }
        if (isCharacter(key, '/')) {
            state.enterSearch();
            state.setStatus("Search user by exact username.");
            return Action.CONTINUE;
        }
        if (isCharacter(key, 'r')) {
            state.enterRequests();
            state.setStatus("Friend requests.");
            return Action.REFRESH_REQUESTS;
        }
        if (isCharacter(key, 'g')) {
            return Action.REFRESH_FRIENDS;
        }
        if (isCharacter(key, 'q')) {
            return Action.QUIT;
        }
        if (key.getKeyType() == KeyType.Enter && friendCount > 0) {
            return Action.SELECT_FRIEND;
        }
        return Action.CONTINUE;
    }

    private Action handleComposerKeys(KeyStroke key, TuiState state) {
        if (key.getKeyType() == KeyType.Enter) {
            if (state.composer().isBlank()) {
                state.setStatus("Type a message first.");
                return Action.CONTINUE;
            }
            if (state.pendingNewChat() != null) {
                return Action.SEND_FIRST_MESSAGE;
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

    private Action handleSearchKeys(KeyStroke key, TuiState state) {
        if (key.getKeyType() == KeyType.Escape) {
            state.exitToChat();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Tab || key.getKeyType() == KeyType.ReverseTab) {
            state.toggleSearchFocus();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Enter) {
            if (state.searchFocus() == TuiState.SearchFocus.SEND) {
                return Action.SEND_FRIEND_REQUEST;
            }
            if (state.searchInput().isBlank()) {
                state.setStatus("Type a username first.");
                return Action.CONTINUE;
            }
            return Action.LOOKUP_USER;
        }
        if (key.getKeyType() == KeyType.Backspace) {
            if (state.searchFocus() == TuiState.SearchFocus.SEND) {
                state.focusSearchInput();
                return Action.CONTINUE;
            }
            state.backspaceSearch();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Character && !key.isCtrlDown() && !key.isAltDown()) {
            Character c = key.getCharacter();
            if (c != null && !Character.isISOControl(c)) {
                if (state.searchFocus() == TuiState.SearchFocus.SEND) {
                    state.focusSearchInput();
                }
                state.appendToSearch(c);
            }
            return Action.CONTINUE;
        }
        return Action.CONTINUE;
    }

    private Action handleRequestKeys(KeyStroke key, TuiState state, int incomingCount, int outgoingCount) {
        if (key.getKeyType() == KeyType.Escape || isCharacter(key, 'q')) {
            state.exitToChat();
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.Tab || key.getKeyType() == KeyType.ReverseTab) {
            state.toggleRequestSection();
            return Action.CONTINUE;
        }
        int count = state.requestSection() == TuiState.RequestSection.INCOMING
                ? incomingCount
                : outgoingCount;
        if (key.getKeyType() == KeyType.ArrowUp || isCharacter(key, 'k')) {
            state.selectRequestUp(count);
            return Action.CONTINUE;
        }
        if (key.getKeyType() == KeyType.ArrowDown || isCharacter(key, 'j')) {
            state.selectRequestDown(count);
            return Action.CONTINUE;
        }
        if (isCharacter(key, 'g')) {
            return Action.REFRESH_REQUESTS;
        }
        if (isCharacter(key, 'a')) {
            return Action.ACCEPT_REQUEST;
        }
        if (isCharacter(key, 'x')) {
            return Action.REJECT_REQUEST;
        }
        if (isCharacter(key, 'c')) {
            return Action.CANCEL_REQUEST;
        }
        if (key.getKeyType() == KeyType.Enter && count > 0) {
            return state.requestSection() == TuiState.RequestSection.INCOMING
                    ? Action.ACCEPT_REQUEST
                    : Action.CANCEL_REQUEST;
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

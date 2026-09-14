package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.samvaad.tui.model.ConversationEntry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TuiControllerFriendsTest {

    private final TuiController controller = new TuiController();

    private static KeyStroke key(KeyType type) {
        return new KeyStroke(type);
    }

    private static KeyStroke ch(char c) {
        return new KeyStroke(c, false, false);
    }

    private static KeyStroke ctrl(char c) {
        return new KeyStroke(c, true, false);
    }

    @Test
    void arrowsSwitchTabsOnlyWithSidebarFocus() {
        TuiState state = new TuiState();
        assertEquals(TuiState.SidebarTab.CONVERSATIONS, state.sidebarTab());

        TuiController.Action right =
                controller.handle(key(KeyType.ArrowRight), state, List.of(), 0, 0, 2);
        assertEquals(TuiState.SidebarTab.FRIENDS, state.sidebarTab());
        assertEquals(TuiController.Action.REFRESH_FRIENDS, right);

        TuiController.Action left =
                controller.handle(key(KeyType.ArrowLeft), state, List.of(), 0, 0, 2);
        assertEquals(TuiState.SidebarTab.CONVERSATIONS, state.sidebarTab());
        assertEquals(TuiController.Action.CONTINUE, left);
    }

    @Test
    void arrowsDoNotSwitchTabsFromComposer() {
        TuiState state = new TuiState();
        state.toggleFocus();
        state.appendToComposer('x');

        controller.handle(key(KeyType.ArrowRight), state, List.of(), 0, 0, 2);
        controller.handle(key(KeyType.ArrowLeft), state, List.of(), 0, 0, 2);

        assertEquals(TuiState.SidebarTab.CONVERSATIONS, state.sidebarTab());
        assertEquals("x", state.composer(), "composer input untouched");
    }

    @Test
    void arrowsDoNotSwitchTabsOutsideChatView() {
        TuiState search = new TuiState();
        search.enterSearch();

        controller.handle(key(KeyType.ArrowRight), search, List.of(), 0, 0, 2);

        assertEquals(TuiState.View.SEARCH, search.view());
    }

    @Test
    void friendNavigationDoesNotWrap() {
        TuiState state = new TuiState();
        state.showFriendsTab();

        controller.handle(ch('j'), state, List.of(), 0, 0, 2);
        controller.handle(ch('j'), state, List.of(), 0, 0, 2);
        controller.handle(ch('j'), state, List.of(), 0, 0, 2);
        assertEquals(1, state.friendSelectedIndex());

        controller.handle(key(KeyType.ArrowUp), state, List.of(), 0, 0, 2);
        controller.handle(ch('k'), state, List.of(), 0, 0, 2);
        assertEquals(0, state.friendSelectedIndex());
    }

    @Test
    void enterSelectsFriendOnlyWhenFriendsExist() {
        TuiState withFriends = new TuiState();
        withFriends.showFriendsTab();

        assertEquals(TuiController.Action.SELECT_FRIEND,
                controller.handle(key(KeyType.Enter), withFriends, List.of(), 0, 0, 2));

        TuiState empty = new TuiState();
        empty.showFriendsTab();

        assertEquals(TuiController.Action.CONTINUE,
                controller.handle(key(KeyType.Enter), empty, List.of(), 0, 0, 0));
    }

    @Test
    void gRefreshesFriendsFromFriendsTab() {
        TuiState state = new TuiState();
        state.showFriendsTab();

        assertEquals(TuiController.Action.REFRESH_FRIENDS,
                controller.handle(ch('g'), state, List.of(), 0, 0, 0));
    }

    @Test
    void sidebarShortcutsStillWorkFromFriendsTab() {
        TuiState search = new TuiState();
        search.showFriendsTab();
        controller.handle(ch('/'), search, List.of(), 0, 0, 1);
        assertEquals(TuiState.View.SEARCH, search.view());

        TuiState requests = new TuiState();
        requests.showFriendsTab();
        assertEquals(TuiController.Action.REFRESH_REQUESTS,
                controller.handle(ch('r'), requests, List.of(), 0, 0, 1));

        TuiState quit = new TuiState();
        quit.showFriendsTab();
        assertEquals(TuiController.Action.QUIT,
                controller.handle(ch('q'), quit, List.of(), 0, 0, 1));
    }

    @Test
    void composerEnterSendsFirstMessageWhilePending() {
        TuiState state = new TuiState();
        state.toggleFocus();
        state.appendToComposer('h');
        state.appendToComposer('i');
        state.startNewChat(UUID.randomUUID(), "bob");

        assertEquals(TuiController.Action.SEND_FIRST_MESSAGE,
                controller.handle(key(KeyType.Enter), state, List.of(), 0, 0, 1));
    }

    @Test
    void composerEnterSendsNormallyWithoutPending() {
        TuiState state = new TuiState();
        state.toggleFocus();
        state.appendToComposer('h');
        state.appendToComposer('i');
        assertNull(state.pendingNewChat());

        assertEquals(TuiController.Action.SEND,
                controller.handle(key(KeyType.Enter), state, List.of(), 0, 0, 1));
    }

    @Test
    void escClearsPendingNewChat() {
        TuiState state = new TuiState();
        state.startNewChat(UUID.randomUUID(), "bob");
        state.toggleFocus();

        TuiController.Action action =
                controller.handle(key(KeyType.Escape), state, List.of(), 0, 0, 1);

        assertEquals(TuiController.Action.CONTINUE, action);
        assertNull(state.pendingNewChat(), "pending new chat cleared");
        assertEquals(TuiState.Focus.CONVERSATIONS, state.focus(), "existing focus behavior kept");
        assertEquals(TuiState.View.CHAT, state.view());
    }

    @Test
    void existingBindingsIntact() {
        TuiState help = new TuiState();
        controller.handle(ch('?'), help, List.of(), 0, 0, 1);
        assertTrue(help.helpVisible());

        TuiState f10 = new TuiState();
        assertEquals(TuiController.Action.QUIT,
                controller.handle(key(KeyType.F10), f10, List.of(), 0, 0, 1));

        TuiState ctrlC = new TuiState();
        assertEquals(TuiController.Action.QUIT,
                controller.handle(ctrl('c'), ctrlC, List.of(), 0, 0, 1));

        TuiState tab = new TuiState();
        controller.handle(key(KeyType.Tab), tab, List.of(), 0, 0, 1);
        assertEquals(TuiState.Focus.COMPOSER, tab.focus());
    }

    @Test
    void tabSwitchResetsFriendSelection() {
        TuiState state = new TuiState();
        state.showFriendsTab();
        state.selectFriendDown(3);
        state.selectFriendDown(3);

        controller.handle(key(KeyType.ArrowLeft), state, List.of(), 0, 0, 3);
        controller.handle(key(KeyType.ArrowRight), state, List.of(), 0, 0, 3);

        assertEquals(TuiState.SidebarTab.FRIENDS, state.sidebarTab());
        assertEquals(0, state.friendSelectedIndex());
    }

    @Test
    void conversationListNavigationUnaffected() {
        TuiState state = new TuiState();
        List<ConversationEntry> conversations = List.of();

        controller.handle(ch('j'), state, conversations, 0, 0, 2);
        assertEquals(0, state.selectedIndex());
        assertEquals(TuiState.SidebarTab.CONVERSATIONS, state.sidebarTab());
    }
}

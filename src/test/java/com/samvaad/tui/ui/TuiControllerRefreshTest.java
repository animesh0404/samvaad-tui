package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiControllerRefreshTest {

    private final TuiController controller = new TuiController();

    private static KeyStroke key(KeyType type) {
        return new KeyStroke(type);
    }

    private static KeyStroke ch(char c) {
        return new KeyStroke(c, false, false);
    }

    @Test
    void f5InConversationsTriggersConversationRefresh() {
        TuiState sidebar = new TuiState();
        assertEquals(TuiController.Action.REFRESH_CONVERSATIONS,
                controller.handle(key(KeyType.F5), sidebar, List.of(), 0, 0, 0));

        TuiState composer = new TuiState();
        composer.toggleFocus();
        assertEquals(TuiController.Action.REFRESH_CONVERSATIONS,
                controller.handle(key(KeyType.F5), composer, List.of(), 0, 0, 0),
                "F5 is not stolen by composer focus");
    }

    @Test
    void f5InFriendsTriggersFriendsRefresh() {
        TuiState sidebar = new TuiState();
        sidebar.showFriendsTab();
        assertEquals(TuiController.Action.REFRESH_FRIENDS,
                controller.handle(key(KeyType.F5), sidebar, List.of(), 0, 0, 2));

        TuiState composer = new TuiState();
        composer.showFriendsTab();
        composer.toggleFocus();
        assertEquals(TuiController.Action.REFRESH_FRIENDS,
                controller.handle(key(KeyType.F5), composer, List.of(), 0, 0, 2));
    }

    @Test
    void f5InRequestsTriggersRequestRefresh() {
        TuiState incoming = new TuiState();
        incoming.enterRequests();
        assertEquals(TuiController.Action.REFRESH_REQUESTS,
                controller.handle(key(KeyType.F5), incoming, List.of(), 1, 0));

        TuiState outgoing = new TuiState();
        outgoing.enterRequests();
        outgoing.toggleRequestSection();
        assertEquals(TuiController.Action.REFRESH_REQUESTS,
                controller.handle(key(KeyType.F5), outgoing, List.of(), 0, 1));
    }

    @Test
    void f5InSearchReusesLookup() {
        TuiState state = new TuiState();
        state.enterSearch();
        state.appendToSearch('b');

        assertEquals(TuiController.Action.LOOKUP_USER,
                controller.handle(key(KeyType.F5), state, List.of(), 0, 0, 0));
    }

    @Test
    void f5DismissesHelpThenRefreshes() {
        TuiState state = new TuiState();
        state.toggleHelp();

        TuiController.Action action =
                controller.handle(key(KeyType.F5), state, List.of(), 0, 0, 0);

        assertEquals(TuiController.Action.REFRESH_CONVERSATIONS, action);
        assertTrue(!state.helpVisible(), "help closes like any other non-help key");
    }

    @Test
    void existingGRefreshStillWorks() {
        TuiState requests = new TuiState();
        requests.enterRequests();
        assertEquals(TuiController.Action.REFRESH_REQUESTS,
                controller.handle(ch('g'), requests, List.of(), 1, 0));

        TuiState friends = new TuiState();
        friends.showFriendsTab();
        assertEquals(TuiController.Action.REFRESH_FRIENDS,
                controller.handle(ch('g'), friends, List.of(), 0, 0, 1));
    }

    @Test
    void f5NeverQuits() {
        for (TuiState state : List.of(new TuiState(), withFriends(), withRequests())) {
            TuiController.Action action =
                    controller.handle(key(KeyType.F5), state, List.of(), 0, 0, 0);
            assertTrue(action != TuiController.Action.QUIT, "F5 must never quit");
            assertTrue(action != TuiController.Action.CONTINUE, "F5 must refresh something");
        }
    }

    private static TuiState withFriends() {
        TuiState state = new TuiState();
        state.showFriendsTab();
        return state;
    }

    private static TuiState withRequests() {
        TuiState state = new TuiState();
        state.enterRequests();
        return state;
    }
}

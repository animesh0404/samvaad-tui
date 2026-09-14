package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.samvaad.tui.model.ConversationEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiControllerSearchTest {

    private final TuiController controller = new TuiController();

    private static KeyStroke key(KeyType type) {
        return new KeyStroke(type);
    }

    private static KeyStroke ch(char c) {
        return new KeyStroke(c, false, false);
    }

    @Test
    void slashOpensSearchFromConversationList() {
        TuiState state = new TuiState();

        TuiController.Action action =
                controller.handle(ch('/'), state, List.of(), 0, 0);

        assertEquals(TuiController.Action.CONTINUE, action);
        assertEquals(TuiState.View.SEARCH, state.view());
    }

    @Test
    void rOpensRequestsAndAsksForRefresh() {
        TuiState state = new TuiState();

        TuiController.Action action =
                controller.handle(ch('r'), state, List.of(), 0, 0);

        assertEquals(TuiController.Action.REFRESH_REQUESTS, action);
        assertEquals(TuiState.View.REQUESTS, state.view());
    }

    @Test
    void slashDoesNotFireFromComposer() {
        TuiState state = new TuiState();
        state.toggleFocus();

        controller.handle(ch('/'), state, List.of(), 0, 0);

        assertEquals(TuiState.View.CHAT, state.view());
        assertEquals("/", state.composer());
    }

    @Test
    void quitBindingsStillQuitFromNewViews() {
        TuiState search = new TuiState();
        search.enterSearch();
        assertEquals(TuiController.Action.QUIT,
                controller.handle(key(KeyType.F10), search, List.of(), 0, 0));

        TuiState requests = new TuiState();
        requests.enterRequests();
        assertEquals(TuiController.Action.QUIT,
                controller.handle(key(KeyType.F10), requests, List.of(), 0, 0));
    }

    @Test
    void typingEditsSearchInputAndEnterLooksUp() {
        TuiState state = new TuiState();
        state.enterSearch();

        controller.handle(ch('b'), state, List.of(), 0, 0);
        controller.handle(ch('o'), state, List.of(), 0, 0);
        controller.handle(ch('b'), state, List.of(), 0, 0);
        assertEquals("bob", state.searchInput());

        TuiController.Action action =
                controller.handle(key(KeyType.Enter), state, List.of(), 0, 0);
        assertEquals(TuiController.Action.LOOKUP_USER, action);
    }

    @Test
    void blankSearchEnterStaysPut() {
        TuiState state = new TuiState();
        state.enterSearch();

        TuiController.Action action =
                controller.handle(key(KeyType.Enter), state, List.of(), 0, 0);

        assertEquals(TuiController.Action.CONTINUE, action);
        assertEquals("Type a username first.", state.status());
    }

    @Test
    void searchBackspaceAndEscape() {
        TuiState state = new TuiState();
        state.enterSearch();
        controller.handle(ch('b'), state, List.of(), 0, 0);

        controller.handle(key(KeyType.Backspace), state, List.of(), 0, 0);
        assertEquals("", state.searchInput());

        controller.handle(key(KeyType.Escape), state, List.of(), 0, 0);
        assertEquals(TuiState.View.CHAT, state.view());
    }

    @Test
    void searchTabMovesToSendAndEnterSends() {
        TuiState state = new TuiState();
        state.enterSearch();
        controller.handle(ch('b'), state, List.of(), 0, 0);

        controller.handle(key(KeyType.Tab), state, List.of(), 0, 0);
        assertEquals(TuiState.SearchFocus.SEND, state.searchFocus());

        TuiController.Action action =
                controller.handle(key(KeyType.Enter), state, List.of(), 0, 0);
        assertEquals(TuiController.Action.SEND_FRIEND_REQUEST, action);
    }

    @Test
    void typingWhileSendFocusedReturnsToInput() {
        TuiState state = new TuiState();
        state.enterSearch();
        state.toggleSearchFocus();

        controller.handle(ch('x'), state, List.of(), 0, 0);

        assertEquals(TuiState.SearchFocus.INPUT, state.searchFocus());
        assertEquals("x", state.searchInput());
    }

    @Test
    void requestsTabSwitchesSectionAndResetsSelection() {
        TuiState state = new TuiState();
        state.enterRequests();
        state.selectRequestDown(3);
        assertEquals(TuiState.RequestSection.INCOMING, state.requestSection());

        controller.handle(key(KeyType.Tab), state, List.of(), 2, 1);

        assertEquals(TuiState.RequestSection.OUTGOING, state.requestSection());
        assertEquals(0, state.requestSelectedIndex());
    }

    @Test
    void requestsNavigationDoesNotWrap() {
        TuiState state = new TuiState();
        state.enterRequests();

        controller.handle(ch('j'), state, List.of(), 2, 0);
        controller.handle(ch('j'), state, List.of(), 2, 0);
        controller.handle(ch('j'), state, List.of(), 2, 0);
        assertEquals(1, state.requestSelectedIndex());

        controller.handle(ch('k'), state, List.of(), 2, 0);
        controller.handle(ch('k'), state, List.of(), 2, 0);
        assertEquals(0, state.requestSelectedIndex());
    }

    @Test
    void requestsActions() {
        TuiState incoming = new TuiState();
        incoming.enterRequests();
        assertEquals(TuiController.Action.ACCEPT_REQUEST,
                controller.handle(ch('a'), incoming, List.of(), 1, 0));
        assertEquals(TuiController.Action.REJECT_REQUEST,
                controller.handle(ch('x'), incoming, List.of(), 1, 0));
        assertEquals(TuiController.Action.ACCEPT_REQUEST,
                controller.handle(key(KeyType.Enter), incoming, List.of(), 1, 0));
        assertEquals(TuiController.Action.REFRESH_REQUESTS,
                controller.handle(ch('g'), incoming, List.of(), 1, 0));

        TuiState outgoing = new TuiState();
        outgoing.enterRequests();
        outgoing.toggleRequestSection();
        assertEquals(TuiController.Action.CANCEL_REQUEST,
                controller.handle(ch('c'), outgoing, List.of(), 0, 1));
        assertEquals(TuiController.Action.CANCEL_REQUEST,
                controller.handle(key(KeyType.Enter), outgoing, List.of(), 0, 1));
    }

    @Test
    void requestsEscapeAndQReturnToChat() {
        TuiState escaped = new TuiState();
        escaped.enterRequests();
        controller.handle(key(KeyType.Escape), escaped, List.of(), 0, 0);
        assertEquals(TuiState.View.CHAT, escaped.view());

        TuiState quit = new TuiState();
        quit.enterRequests();
        TuiController.Action action =
                controller.handle(ch('q'), quit, List.of(), 0, 0);
        assertEquals(TuiController.Action.CONTINUE, action);
        assertEquals(TuiState.View.CHAT, quit.view());
    }

    @Test
    void chatNavigationUnchanged() {
        TuiState state = new TuiState();
        List<ConversationEntry> conversations = List.of();

        controller.handle(ch('j'), state, conversations, 0, 0);
        assertEquals(0, state.selectedIndex());
        assertEquals(TuiState.View.CHAT, state.view());

        TuiController.Action quit = controller.handle(ch('q'), state, conversations, 0, 0);
        assertEquals(TuiController.Action.QUIT, quit);
    }

    @Test
    void helpToggleWorksFromNewViews() {
        TuiState state = new TuiState();
        state.enterSearch();

        controller.handle(key(KeyType.F1), state, List.of(), 0, 0);
        assertTrue(state.helpVisible());

        controller.handle(key(KeyType.Escape), state, List.of(), 0, 0);
        assertEquals(TuiState.View.SEARCH, state.view(), "Esc first closes help");
    }
}

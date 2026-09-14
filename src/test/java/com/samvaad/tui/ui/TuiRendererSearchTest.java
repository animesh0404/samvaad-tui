package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.FriendRequestEntry;
import com.samvaad.tui.model.FriendRequestStatus;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.model.UserLookupEntry;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TuiRendererSearchTest {

    private static final String USER = "alice";
    private static final String SERVER = "http://localhost:8080";
    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static ConversationStore chatStore() {
        ConversationStore store = new ConversationStore(ME, List.of(
                new ConversationEntry(UUID.randomUUID(), BOB_ID, "bob", 1,
                        LocalDateTime.of(2026, 9, 14, 10, 0))));
        return store;
    }

    private static FriendRequestEntry request(String username) {
        return new FriendRequestEntry(UUID.randomUUID(), UUID.randomUUID(), username,
                ME, "alice", FriendRequestStatus.PENDING,
                LocalDateTime.of(2026, 9, 14, 10, 0), null);
    }

    private static String screenText(Screen screen, int cols, int rows) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                text.append(screen.getFrontCharacter(col, row).getCharacter());
            }
            text.append('\n');
        }
        return text.toString();
    }

    @Test
    void searchViewShowsInputAndResult() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.enterSearch();
            state.appendToSearch('b');
            FriendRequestStore friends = new FriendRequestStore();
            friends.putLookupResult(new UserLookupEntry(BOB_ID, "bob"));

            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER, friends);
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("Search user"), "search panel must render");
            assertTrue(text.contains("Found: bob"), "lookup result must render");
            assertTrue(text.contains("Send friend request"), "send action must render");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void searchViewShowsLookupError() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.enterSearch();
            FriendRequestStore friends = new FriendRequestStore();
            friends.putLookupError("User not found.");

            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER, friends);
            screen.refresh();

            assertTrue(screenText(screen, 100, 30).contains("User not found."));
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void requestsViewShowsIncomingAndOutgoing() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.enterRequests();
            FriendRequestStore friends = new FriendRequestStore();
            friends.putIncoming(List.of(request("carol")));
            friends.putOutgoing(List.of(request("dave")));

            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER, friends);
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("Friend requests"), "requests panel must render");
            assertTrue(text.contains("Incoming (1)"), "incoming tab must render");
            assertTrue(text.contains("Outgoing (1)"), "outgoing tab must render");
            assertTrue(text.contains("carol"), "incoming username must render");
            assertTrue(!text.contains("dave"), "inactive section stays hidden");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void requestsViewShowsEmptyAndErrorStates() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.enterRequests();
            FriendRequestStore friends = new FriendRequestStore();
            friends.putIncoming(List.of());
            friends.putOutgoingError("Cannot reach server.");

            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER, friends);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("(none)"));

            state.toggleRequestSection();
            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER, friends);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("Cannot reach server."));
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void helpListsSearchAndRequestBindings() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.toggleHelp();
            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER,
                    new FriendRequestStore());
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("Search user by exact username"));
            assertTrue(text.contains("Friend requests (incoming/outgoing)"));
            assertTrue(text.contains("a accept, x reject, c cancel"));
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void chatViewStillRendersConversations() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            new TuiRenderer().render(
                    screen, new TuiState(), chatStore(), USER, SERVER, new FriendRequestStore());
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("bob"), "chat sidebar must still render");
            assertTrue(!text.contains("Search user"), "search panel must stay hidden in chat");
        } finally {
            screen.stopScreen();
        }
    }
}

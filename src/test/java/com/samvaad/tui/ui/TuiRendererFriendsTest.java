package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.FriendEntry;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.model.FriendStore;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TuiRendererFriendsTest {

    private static final String USER = "alice";
    private static final String SERVER = "http://localhost:8080";
    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static ConversationStore chatStore() {
        return new ConversationStore(ME, List.of(
                new ConversationEntry(UUID.randomUUID(), BOB_ID, "bob", 1,
                        LocalDateTime.of(2026, 9, 14, 10, 0))));
    }

    private static FriendStore friendsWith(String... usernames) {
        FriendStore store = new FriendStore();
        store.markLoading();
        List<FriendEntry> entries = new ArrayList<>();
        for (String username : usernames) {
            entries.add(new FriendEntry(UUID.randomUUID(), username));
        }
        store.putFriends(entries);
        return store;
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
    void sidebarShowsBothTabs() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            new TuiRenderer().render(screen, new TuiState(), chatStore(), USER, SERVER,
                    new FriendRequestStore(), friendsWith("bob"));
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("CONVERSATIONS"), "conversations tab must render");
            assertTrue(text.contains("FRIENDS"), "friends tab must render");
            assertTrue(text.contains("Conversations"), "sidebar box title kept");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void friendsTabShowsListAndSelection() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.showFriendsTab();
            state.selectFriendDown(2);

            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER,
                    new FriendRequestStore(), friendsWith("anna", "mike"));
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("anna"), "friend listed");
            assertTrue(text.contains("mike"), "friend listed");
            assertTrue(text.contains("> mike"), "selected friend is obvious");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void friendsTabShowsEmptyState() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.showFriendsTab();

            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER,
                    new FriendRequestStore(), new FriendStore());
            screen.refresh();

            assertTrue(screenText(screen, 100, 30).contains("no friends yet"),
                    "empty friends tab needs a message");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void conversationsTabStillRendersConversations() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            new TuiRenderer().render(screen, new TuiState(), chatStore(), USER, SERVER,
                    new FriendRequestStore(), friendsWith("anna"));
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("bob"), "conversations tab unchanged by default");
            assertTrue(text.contains("no conversations") || !text.contains("(no friends yet)"),
                    "friends empty state must not leak into conversations tab");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void pendingNewChatShowsClearIndication() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.startNewChat(BOB_ID, "bob");

            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER,
                    new FriendRequestStore(), friendsWith("bob"));
            screen.refresh();

            assertTrue(screenText(screen, 100, 30).contains("New chat with bob"),
                    "pending new chat must be obvious");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void helpListsFriendsBindings() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.toggleHelp();
            new TuiRenderer().render(screen, state, chatStore(), USER, SERVER,
                    new FriendRequestStore(), new FriendStore());
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("Left/Right"), "help lists the tab binding");
            assertTrue(text.contains("In friends"), "help explains the friends tab");
        } finally {
            screen.stopScreen();
        }
    }
}

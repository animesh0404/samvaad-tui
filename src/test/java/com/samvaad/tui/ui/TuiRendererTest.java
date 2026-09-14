package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TuiRendererTest {

    private static final String USER = "alice";
    private static final String SERVER = "http://localhost:8080";
    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID BOB_CONVERSATION =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CHARLIE_CONVERSATION =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static ConversationEntry entry(UUID conversationId, UUID userId, String username) {
        return new ConversationEntry(conversationId, userId, username, 2,
                LocalDateTime.of(2026, 9, 14, 10, 0));
    }

    private static ConversationStore storeWith(ConversationEntry... entries) {
        return new ConversationStore(ME, List.of(entries));
    }

    private static ConversationStore loadedStore() {
        ConversationStore store = storeWith(
                entry(BOB_CONVERSATION, BOB_ID, "bob"),
                entry(CHARLIE_CONVERSATION, UUID.randomUUID(), null));
        store.markLoading(BOB_CONVERSATION);
        store.putMessages(BOB_CONVERSATION, List.of(
                new MessageEntry(UUID.randomUUID(), BOB_ID, 1, "Hello",
                        LocalDateTime.of(2026, 9, 14, 10, 0), UUID.randomUUID()),
                new MessageEntry(UUID.randomUUID(), ME, 2, "Hi!",
                        LocalDateTime.of(2026, 9, 14, 10, 1), UUID.randomUUID())));
        return store;
    }

    @Test
    void rendersHeaderSidebarAndChat() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            new TuiRenderer().render(screen, new TuiState(), loadedStore(), USER, SERVER);
            screen.refresh();

            assertTrue(row(screen, 0, 100).contains("Samvaad"), "header shows the app name");
            assertTrue(row(screen, 0, 100).contains("alice"), "header shows the user, not tokens");
            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("Conversations"), "sidebar is present");
            assertTrue(text.contains("bob"), "server conversation listed");
            assertTrue(text.contains("Hello"), "history message shown");
            assertTrue(text.contains("You [09-14 10:01]: Hi!"), "own messages labeled");
            assertTrue(text.contains("bob [09-14 10:00]: Hello"), "other messages labeled");
            assertTrue(text.contains("10:00") && text.contains("10:01"),
                    "server timestamps render for every message");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void rendersServerOrderAndNullFallback() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            ConversationStore store = storeWith(
                    entry(CHARLIE_CONVERSATION, UUID.randomUUID(), null),
                    entry(BOB_CONVERSATION, BOB_ID, "bob"));
            new TuiRenderer().render(screen, new TuiState(), store, USER, SERVER);
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.indexOf("Unknown user") < text.indexOf("bob"),
                    "server order must be preserved, not re-sorted");
            assertTrue(text.contains("Unknown user"), "null username needs a fallback");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void rendersEmptyConversations() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            new TuiRenderer().render(
                    screen, new TuiState(), new ConversationStore(ME, List.of()), USER, SERVER);
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(text.contains("no conversations"), "empty sidebar needs a message");
            assertTrue(text.contains("No conversations yet."), "empty chat needs a message");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void rendersLoadingEmptyAndErrorStates() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiRenderer renderer = new TuiRenderer();
            ConversationStore loading = storeWith(entry(BOB_CONVERSATION, BOB_ID, "bob"));
            loading.markLoading(BOB_CONVERSATION);
            renderer.render(screen, new TuiState(), loading, USER, SERVER);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("Loading history"),
                    "loading state must render");

            ConversationStore empty = storeWith(entry(BOB_CONVERSATION, BOB_ID, "bob"));
            empty.markLoading(BOB_CONVERSATION);
            empty.putMessages(BOB_CONVERSATION, List.of());
            renderer.render(screen, new TuiState(), empty, USER, SERVER);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("No messages yet."),
                    "empty history must render");

            ConversationStore failed = storeWith(entry(BOB_CONVERSATION, BOB_ID, "bob"));
            failed.markLoading(BOB_CONVERSATION);
            failed.putError(BOB_CONVERSATION, "Access denied for this conversation.");
            renderer.render(screen, new TuiState(), failed, USER, SERVER);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("Access denied"),
                    "error state must render without stranding");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void focusedPaneTitleIsBold() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiRenderer renderer = new TuiRenderer();
            ConversationStore store = loadedStore();
            TuiState listState = new TuiState();
            renderer.render(screen, listState, store, USER, SERVER);
            screen.refresh();
            assertTrue(screen.getFrontCharacter(2, 1).getModifiers().contains(SGR.BOLD),
                    "sidebar title bold when list focused");
            assertTrue(!screen.getFrontCharacter(31, 1).getModifiers().contains(SGR.BOLD),
                    "chat title plain when list focused");

            TuiState composerState = new TuiState();
            composerState.toggleFocus();
            renderer.render(screen, composerState, store, USER, SERVER);
            screen.refresh();
            assertTrue(!screen.getFrontCharacter(2, 1).getModifiers().contains(SGR.BOLD),
                    "sidebar title plain when composer focused");
            assertTrue(screen.getFrontCharacter(31, 1).getModifiers().contains(SGR.BOLD),
                    "chat title bold when composer focused");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void rendersHelpOverlayWhenVisible() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.toggleHelp();
            new TuiRenderer().render(screen, state, loadedStore(), USER, SERVER);
            screen.refresh();

            assertTrue(screenText(screen, 100, 30).contains("F10"), "help lists the quit binding");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void helpOverlayShowsFullContentWithPadding() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            state.toggleHelp();
            new TuiRenderer().render(screen, state, loadedStore(), USER, SERVER);
            screen.refresh();

            List<String> rows = new ArrayList<>();
            for (int row = 0; row < 30; row++) {
                rows.add(row(screen, row, 100));
            }
            String text = String.join("\n", rows);
            assertTrue(text.contains("Open conversation / send message"),
                    "help line must be current");
            int firstBinding = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).contains("Up/Down")) {
                    firstBinding = i;
                    break;
                }
            }
            assertTrue(firstBinding > 0, "bindings must render");
            String paddingRow = rows.get(firstBinding - 1).strip();
            assertTrue(paddingRow.chars().allMatch(c -> c == ' ' || c == '│'),
                    "one blank padded row must separate the title border from the content");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void closingHelpLeavesNoGhostCharacters() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            TuiRenderer renderer = new TuiRenderer();
            ConversationStore store = loadedStore();
            state.toggleHelp();
            renderer.render(screen, state, store, USER, SERVER);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("Toggle"),
                    "help overlay must render first");

            state.closeHelp();
            renderer.render(screen, state, store, USER, SERVER);
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(!text.contains("Toggle"), "no stale help text may remain, got:\n" + text);
            assertTrue(!text.contains("later phases"), "no stale help text may remain");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void switchingConversationsLeavesNoGhostMessages() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            TuiRenderer renderer = new TuiRenderer();
            ConversationStore store = loadedStore();
            renderer.render(screen, state, store, USER, SERVER);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("Hello"));

            state.selectDown(store.conversations().size());
            store.markLoading(CHARLIE_CONVERSATION);
            store.putMessages(CHARLIE_CONVERSATION, List.of(new MessageEntry(
                    UUID.randomUUID(), UUID.randomUUID(), 1, "Lunch?",
                    LocalDateTime.of(2026, 9, 14, 11, 0), UUID.randomUUID())));
            renderer.render(screen, state, store, USER, SERVER);
            screen.refresh();

            String text = screenText(screen, 100, 30);
            assertTrue(!text.contains("Hello"),
                    "previous conversation messages must be repainted");
            assertTrue(text.contains("Lunch?"), "new conversation messages must render");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void resizeWithHelpOpenRepaintsCleanlyAfterClose() throws IOException {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 30));
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();
        try {
            TuiState state = new TuiState();
            TuiRenderer renderer = new TuiRenderer();
            ConversationStore store = loadedStore();
            state.toggleHelp();
            renderer.render(screen, state, store, USER, SERVER);
            screen.refresh();

            terminal.setTerminalSize(new TerminalSize(70, 20));
            screen.doResizeIfNecessary();
            renderer.render(screen, state, store, USER, SERVER);
            screen.refresh();

            state.closeHelp();
            renderer.render(screen, state, store, USER, SERVER);
            screen.refresh();

            String text = screenText(screen, 70, 20);
            assertTrue(!text.contains("Toggle"), "no stale help text may remain after resize");
            assertTrue(!text.contains("later phases"), "no stale help text may remain after resize");
        } finally {
            screen.stopScreen();
        }
    }

    @Test
    void warnsOnTinyTerminal() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(40, 10)));
        screen.startScreen();
        try {
            new TuiRenderer().render(
                    screen, new TuiState(), new ConversationStore(ME, List.of()), USER, SERVER);
            screen.refresh();

            assertTrue(row(screen, 0, 40).contains("too small"), "tiny terminal gets a clear message");
        } finally {
            screen.stopScreen();
        }
    }

    private static String row(Screen screen, int row, int cols) {
        StringBuilder line = new StringBuilder();
        for (int col = 0; col < cols; col++) {
            line.append(screen.getFrontCharacter(col, row).getCharacter());
        }
        return line.toString();
    }

    private static String screenText(Screen screen, int cols, int rows) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            text.append(row(screen, row, cols)).append('\n');
        }
        return text.toString();
    }
}

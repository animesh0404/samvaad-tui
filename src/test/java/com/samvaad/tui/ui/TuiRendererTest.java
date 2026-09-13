package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiRendererTest {

    private static final String USER = "alice";
    private static final String SERVER = "http://localhost:8080";

    @Test
    void rendersHeaderSidebarAndChat() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 30)));
        screen.startScreen();
        try {
            new TuiRenderer().render(screen, new TuiState(), USER, SERVER);
            screen.refresh();

            assertTrue(row(screen, 0, 100).contains("Samvaad"), "header shows the app name");
            assertTrue(row(screen, 0, 100).contains("alice"), "header shows the user, not tokens");
            assertTrue(screenText(screen, 100, 30).contains("Conversations"), "sidebar is present");
            assertTrue(screenText(screen, 100, 30).contains("Alice"), "preview conversation listed");
            assertTrue(screenText(screen, 100, 30).contains("How are you?"), "chat shows messages");
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
            new TuiRenderer().render(screen, state, USER, SERVER);
            screen.refresh();

            assertTrue(screenText(screen, 100, 30).contains("F10"), "help lists the quit binding");
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
            state.toggleHelp();
            renderer.render(screen, state, USER, SERVER);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("Toggle"),
                    "help overlay must render first");

            state.closeHelp();
            renderer.render(screen, state, USER, SERVER);
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
            renderer.render(screen, state, USER, SERVER);
            screen.refresh();
            assertTrue(screenText(screen, 100, 30).contains("How are you?"));

            state.selectDown(PreviewInbox.conversations().size());
            renderer.render(screen, state, USER, SERVER);
            screen.refresh();

            assertTrue(!screenText(screen, 100, 30).contains("How are you?"),
                    "previous conversation messages must be repainted");
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
            state.toggleHelp();
            renderer.render(screen, state, USER, SERVER);
            screen.refresh();

            terminal.setTerminalSize(new TerminalSize(70, 20));
            screen.doResizeIfNecessary();
            renderer.render(screen, state, USER, SERVER);
            screen.refresh();

            state.closeHelp();
            renderer.render(screen, state, USER, SERVER);
            screen.refresh();

            String text = screenText(screen, 70, 20);
            assertTrue(!text.contains("Toggle"), "no stale help text may remain after resize");
            assertTrue(!text.contains("later phases"), "no stale help text may remain after resize");
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
            new TuiRenderer().render(screen, state, USER, SERVER);
            screen.refresh();

            List<String> rows = new ArrayList<>();
            for (int row = 0; row < 30; row++) {
                rows.add(row(screen, row, 100));
            }
            String text = String.join("\n", rows);
            assertTrue(text.contains("Open conversation / composer notice"),
                    "longest help line must not be clipped");
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
    void warnsOnTinyTerminal() throws IOException {        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(40, 10)));
        screen.startScreen();
        try {
            new TuiRenderer().render(screen, new TuiState(), USER, SERVER);
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

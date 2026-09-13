package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.io.IOException;
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
    void warnsOnTinyTerminal() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(40, 10)));
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

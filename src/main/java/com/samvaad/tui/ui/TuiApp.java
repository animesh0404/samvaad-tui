package com.samvaad.tui.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import java.io.IOException;

/**
 * Fullscreen TUI shell lifecycle: opens the terminal, runs the
 * render/input loop, and always restores the terminal on exit.
 *
 * <p>Receives display data only — never tokens or passwords — and never
 * touches HTTP or realtime code.
 */
public final class TuiApp implements TuiLauncher {

    private final TuiController controller = new TuiController();
    private final TuiRenderer renderer = new TuiRenderer();

    @Override
    public void launch(String username, String serverUrl) {
        Terminal terminal = openTerminal();
        Screen screen;
        try {
            screen = new TerminalScreen(terminal);
        } catch (IOException e) {
            throw new TuiException("Cannot open terminal. Run inside a real terminal.", e);
        }
        Thread shutdownHook = new Thread(() -> stopQuietly(screen), "samvaad-tui-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            screen.startScreen();
            runLoop(screen, username, serverUrl);
        } catch (IOException e) {
            throw new TuiException("Terminal input/output failed.", e);
        } finally {
            stopQuietly(screen);
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // Already shutting down; the hook itself performed cleanup.
            }
        }
    }

    private void runLoop(Screen screen, String username, String serverUrl) throws IOException {
        TuiState state = new TuiState();
        while (true) {
            screen.doResizeIfNecessary();
            renderer.render(screen, state, username, serverUrl);
            screen.refresh();
            KeyStroke key = screen.readInput();
            if (controller.handle(key, state, PreviewInbox.conversations())
                    == TuiController.Action.QUIT) {
                return;
            }
        }
    }

    private Terminal openTerminal() {
        DefaultTerminalFactory factory = new DefaultTerminalFactory();
        factory.setInitialTerminalSize(new TerminalSize(100, 30));
        factory.setUnixTerminalCtrlCBehaviour(UnixLikeTerminal.CtrlCBehaviour.TRAP);
        try {
            return factory.createTerminal();
        } catch (IOException e) {
            throw new TuiException("Cannot open terminal. Run inside a real terminal.", e);
        }
    }

    private static void stopQuietly(Screen screen) {
        try {
            screen.stopScreen();
        } catch (IOException | IllegalStateException ignored) {
            // Best effort: never fail shutdown because cleanup failed.
        }
    }
}

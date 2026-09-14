package com.samvaad.tui.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import com.samvaad.tui.realtime.RealtimeException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Fullscreen TUI shell lifecycle: opens the terminal, runs the
 * render/input loop, and always restores the terminal on exit.
 *
 * <p>Receives display data only — never tokens or passwords — and never
 * touches HTTP or realtime transports directly; realtime coordination
 * goes through the session's manager. History loads on a worker thread
 * so HTTP never blocks the UI thread.
 */
public final class TuiApp implements TuiLauncher {

    static final int HISTORY_LIMIT = 20;
    static final int POLL_MILLIS = 50;

    private final TuiController controller = new TuiController();
    private final TuiRenderer renderer = new TuiRenderer();

    @Override
    public void launch(TuiSession session) {
        Terminal terminal = openTerminal();
        Screen screen;
        try {
            screen = new TerminalScreen(terminal);
        } catch (IOException e) {
            throw new TuiException("Cannot open terminal. Run inside a real terminal.", e);
        }
        Thread shutdownHook = new Thread(() -> {
            stopQuietly(screen);
            session.realtime().disconnect();
        }, "samvaad-tui-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            screen.startScreen();
            runLoop(screen, session);
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

    private void runLoop(Screen screen, TuiSession session) throws IOException {
        TuiState state = new TuiState();
        // Polling loop instead of blocking reads: background completions
        // (history loads, realtime broadcasts, reconnect notices) repaint
        // within one tick even while the user is idle. Diff refresh keeps
        // idle frames cheap.
        while (true) {
            screen.doResizeIfNecessary();
            triggerHistoryLoad(session.store(), session.historyLoader(), state.selectedIndex());
            ensureSubscribed(session, state);
            drainRealtimeNotice(session, state);
            reconcilePendingSend(session.store(), state);
            renderer.render(screen, state, session.store(), session.username(), session.serverUrl());
            screen.refresh();
            KeyStroke key = screen.pollInput();
            if (key == null) {
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            TuiController.Action action =
                    controller.handle(key, state, session.store().conversations());
            if (action == TuiController.Action.QUIT) {
                return;
            }
            if (action == TuiController.Action.SEND) {
                sendComposer(session, state);
            }
        }
    }

    private void ensureSubscribed(TuiSession session, TuiState state) {
        List<ConversationEntry> conversations = session.store().conversations();
        if (conversations.isEmpty()) {
            return;
        }
        UUID selected = conversations.get(
                Math.min(state.selectedIndex(), conversations.size() - 1)).conversationId();
        session.realtime().switchTo(selected);
    }

    private void sendComposer(TuiSession session, TuiState state) {
        List<ConversationEntry> conversations = session.store().conversations();
        if (conversations.isEmpty()) {
            return;
        }
        UUID selected = conversations.get(
                Math.min(state.selectedIndex(), conversations.size() - 1)).conversationId();
        String text = state.composer();
        UUID requestId;
        try {
            requestId = session.realtime().send(selected, text);
        } catch (RealtimeException e) {
            state.setStatus("Send failed (" + e.getMessage() + ").");
            return;
        }
        state.clearComposer();
        state.setPendingSend(requestId);
        state.setStatus("Sending...");
    }

    private void drainRealtimeNotice(TuiSession session, TuiState state) {
        String notice = session.realtime().takeNotice();
        if (notice != null) {
            state.setStatus(notice);
        }
    }

    /**
     * Clears the pending send once its authoritative broadcast is present
     * locally. Only the matching idempotency key clears it; unrelated
     * messages leave it pending, and a synchronously failed send never
     * sets it in the first place.
     */
    static void reconcilePendingSend(ConversationStore store, TuiState state) {
        UUID pending = state.pendingSend();
        if (pending != null && store.containsRequestId(pending)) {
            state.clearPendingSend();
            state.setStatus("Sent.");
        }
    }

    /**
     * Starts a daemon worker loading initial history for the selected
     * conversation the first time it is selected. Loaded, loading, and
     * errored conversations are left alone, so failures stay visible
     * instead of turning into a request loop.
     */
    static void triggerHistoryLoad(
            ConversationStore store, MessageHistoryLoader loader, int selectedIndex) {
        List<ConversationEntry> conversations = store.conversations();
        if (conversations.isEmpty()) {
            return;
        }
        UUID conversationId = conversations.get(
                Math.min(selectedIndex, conversations.size() - 1)).conversationId();
        if (!store.markLoading(conversationId)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                List<MessageEntry> messages = loader.load(conversationId, 0, HISTORY_LIMIT);
                store.putMessages(conversationId, messages);
            } catch (SamvaadApiException e) {
                store.putError(conversationId, userMessage(e));
            } catch (RuntimeException e) {
                store.putError(conversationId, "Could not load history.");
            }
        }, "samvaad-history-loader");
        worker.setDaemon(true);
        worker.start();
    }

    static String userMessage(SamvaadApiException e) {
        if (e.kind() == SamvaadApiException.Kind.SERVER_UNAVAILABLE) {
            return "Cannot reach server.";
        }
        if (e.kind() == SamvaadApiException.Kind.MALFORMED_RESPONSE) {
            return "Malformed server response.";
        }
        if (e.kind() == SamvaadApiException.Kind.AUTHENTICATION_FAILED) {
            return "Session expired. Restart and log in again.";
        }
        return switch (e.statusCode()) {
            case 403 -> "Access denied for this conversation.";
            case 404 -> "Conversation unavailable.";
            case 400 -> "Invalid history request.";
            default -> "Could not load history (HTTP " + e.statusCode() + ").";
        };
    }

    private Terminal openTerminal() {        DefaultTerminalFactory factory = new DefaultTerminalFactory();
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

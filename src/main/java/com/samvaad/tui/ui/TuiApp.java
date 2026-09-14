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
import com.samvaad.tui.model.FriendRequestEntry;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.model.MessageEntry;
import com.samvaad.tui.model.UserLookupEntry;
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
    static final long REQUEST_REFRESH_MILLIS = 30_000;

    private final TuiController controller = new TuiController();
    private final TuiRenderer renderer = new TuiRenderer();
    private volatile boolean requestsRefreshing;
    private volatile long lastRequestRefresh;

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
            maybeRefreshRequests(session, state);
            renderer.render(screen, state, session.store(), session.username(), session.serverUrl(),
                    session.friendStore());
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
            TuiController.Action action = controller.handle(key, state,
                    session.store().conversations(),
                    session.friendStore().incoming().size(),
                    session.friendStore().outgoing().size());
            if (action == TuiController.Action.QUIT) {
                return;
            }
            if (action == TuiController.Action.SEND) {
                sendComposer(session, state);
            }
            if (action == TuiController.Action.LOOKUP_USER) {
                lookupUser(session, state);
            }
            if (action == TuiController.Action.SEND_FRIEND_REQUEST) {
                sendFriendRequest(session, state);
            }
            if (action == TuiController.Action.ACCEPT_REQUEST) {
                acceptRequest(session, state);
            }
            if (action == TuiController.Action.REJECT_REQUEST) {
                rejectRequest(session, state);
            }
            if (action == TuiController.Action.CANCEL_REQUEST) {
                cancelRequest(session, state);
            }
            if (action == TuiController.Action.REFRESH_REQUESTS) {
                refreshRequests(session, state, true);
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
     * Looks up the username currently in the search box on a daemon
     * worker so HTTP never blocks the UI thread.
     */
    private void lookupUser(TuiSession session, TuiState state) {
        String username = state.searchInput();
        if (username.isBlank()) {
            state.setStatus("Type a username first.");
            return;
        }
        FriendRequestStore friendStore = session.friendStore();
        friendStore.markLookupLoading();
        state.setStatus("Looking up " + username.trim() + "...");
        Thread worker = new Thread(() -> {
            try {
                UserLookupEntry found = session.friends().lookup(username);
                friendStore.putLookupResult(found);
                state.setStatus("Found " + found.username() + ". Tab to send, Enter on Send.");
            } catch (SamvaadApiException e) {
                friendStore.putLookupError(friendLookupMessage(e));
                state.setStatus(friendLookupMessage(e));
            } catch (RuntimeException e) {
                friendStore.putLookupError("Could not look up user.");
                state.setStatus("Could not look up user.");
            }
        }, "samvaad-user-lookup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Sends a friend request to the last successfully looked-up user.
     */
    private void sendFriendRequest(TuiSession session, TuiState state) {
        UserLookupEntry target = session.friendStore().lookupResult();
        if (target == null) {
            state.setStatus("Look up a user first.");
            return;
        }
        state.setStatus("Sending friend request to " + target.username() + "...");
        Thread worker = new Thread(() -> {
            try {
                session.friends().sendRequest(target.username());
                state.setStatus("Friend request sent to " + target.username() + ".");
                refreshRequests(session, state, true);
            } catch (SamvaadApiException e) {
                state.setStatus(friendSendMessage(e));
            } catch (RuntimeException e) {
                state.setStatus("Could not send friend request.");
            }
        }, "samvaad-friend-send");
        worker.setDaemon(true);
        worker.start();
    }

    private void acceptRequest(TuiSession session, TuiState state) {
        FriendRequestEntry selected = selectedIncoming(session, state);
        if (selected == null) {
            state.setStatus("No incoming request selected.");
            return;
        }
        state.setStatus("Accepting request from " + selected.senderUsername() + "...");
        Thread worker = new Thread(() -> {
            try {
                FriendRequestEntry updated = session.friends().accept(selected.requestId());
                state.setStatus("Accepted friend request from " + updated.senderUsername() + ".");
                refreshRequests(session, state, true);
            } catch (SamvaadApiException e) {
                state.setStatus(friendMutationMessage(e, "Accept"));
            } catch (RuntimeException e) {
                state.setStatus("Could not accept request.");
            }
        }, "samvaad-friend-accept");
        worker.setDaemon(true);
        worker.start();
    }

    private void rejectRequest(TuiSession session, TuiState state) {
        FriendRequestEntry selected = selectedIncoming(session, state);
        if (selected == null) {
            state.setStatus("No incoming request selected.");
            return;
        }
        state.setStatus("Rejecting request from " + selected.senderUsername() + "...");
        Thread worker = new Thread(() -> {
            try {
                FriendRequestEntry updated = session.friends().reject(selected.requestId());
                state.setStatus("Rejected friend request from " + updated.senderUsername() + ".");
                refreshRequests(session, state, true);
            } catch (SamvaadApiException e) {
                state.setStatus(friendMutationMessage(e, "Reject"));
            } catch (RuntimeException e) {
                state.setStatus("Could not reject request.");
            }
        }, "samvaad-friend-reject");
        worker.setDaemon(true);
        worker.start();
    }

    private void cancelRequest(TuiSession session, TuiState state) {
        FriendRequestEntry selected = selectedOutgoing(session, state);
        if (selected == null) {
            state.setStatus("No outgoing request selected.");
            return;
        }
        state.setStatus("Cancelling request to " + selected.recipientUsername() + "...");
        Thread worker = new Thread(() -> {
            try {
                FriendRequestEntry updated = session.friends().cancel(selected.requestId());
                state.setStatus(
                        "Cancelled friend request to " + updated.recipientUsername() + ".");
                refreshRequests(session, state, true);
            } catch (SamvaadApiException e) {
                state.setStatus(friendMutationMessage(e, "Cancel"));
            } catch (RuntimeException e) {
                state.setStatus("Could not cancel request.");
            }
        }, "samvaad-friend-cancel");
        worker.setDaemon(true);
        worker.start();
    }

    private static FriendRequestEntry selectedIncoming(TuiSession session, TuiState state) {
        if (state.requestSection() != TuiState.RequestSection.INCOMING) {
            return null;
        }
        List<FriendRequestEntry> incoming = session.friendStore().incoming();
        if (incoming.isEmpty()) {
            return null;
        }
        int index = Math.min(state.requestSelectedIndex(), incoming.size() - 1);
        return incoming.get(index);
    }

    private static FriendRequestEntry selectedOutgoing(TuiSession session, TuiState state) {
        if (state.requestSection() != TuiState.RequestSection.OUTGOING) {
            return null;
        }
        List<FriendRequestEntry> outgoing = session.friendStore().outgoing();
        if (outgoing.isEmpty()) {
            return null;
        }
        int index = Math.min(state.requestSelectedIndex(), outgoing.size() - 1);
        return outgoing.get(index);
    }

    /**
     * Refreshes both pending lists on a daemon worker. Explicit refreshes
     * (entering the view, mutations, manual key) always run; the periodic
     * tick only refreshes while the REQUESTS view is visible.
     */
    private void refreshRequests(TuiSession session, TuiState state, boolean explicit) {
        if (!explicit && state.view() != TuiState.View.REQUESTS) {
            return;
        }
        if (requestsRefreshing) {
            return;
        }
        requestsRefreshing = true;
        lastRequestRefresh = System.currentTimeMillis();
        FriendRequestStore friendStore = session.friendStore();
        friendStore.markIncomingLoading();
        friendStore.markOutgoingLoading();
        Thread worker = new Thread(() -> {
            try {
                try {
                    friendStore.putIncoming(session.friends().refreshIncoming());
                } catch (SamvaadApiException e) {
                    friendStore.putIncomingError(friendListMessage(e));
                }
                try {
                    friendStore.putOutgoing(session.friends().refreshOutgoing());
                } catch (SamvaadApiException e) {
                    friendStore.putOutgoingError(friendListMessage(e));
                }
                state.clampRequestSelection(
                        state.requestSection() == TuiState.RequestSection.INCOMING
                                ? friendStore.incoming().size()
                                : friendStore.outgoing().size());
            } catch (RuntimeException e) {
                friendStore.putIncomingError("Could not load friend requests.");
                friendStore.putOutgoingError("Could not load friend requests.");
            } finally {
                requestsRefreshing = false;
            }
        }, "samvaad-friend-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Periodic refresh while the REQUESTS view is visible. Background
     * completions repaint through the existing polling render loop.
     */
    private void maybeRefreshRequests(TuiSession session, TuiState state) {
        if (state.view() != TuiState.View.REQUESTS || requestsRefreshing) {
            return;
        }
        if (System.currentTimeMillis() - lastRequestRefresh >= REQUEST_REFRESH_MILLIS) {
            refreshRequests(session, state, false);
        }
    }

    static String friendLookupMessage(SamvaadApiException e) {
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
            case 400 -> "Invalid username.";
            case 404 -> "User not found.";
            default -> "User lookup failed (HTTP " + e.statusCode() + ").";
        };
    }

    static String friendSendMessage(SamvaadApiException e) {
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
            case 400 -> "Invalid username.";
            case 403 -> "Cannot send a friend request to yourself.";
            case 404 -> "User not found.";
            case 409 -> "Friend request already pending or already friends.";
            default -> "Send friend request failed (HTTP " + e.statusCode() + ").";
        };
    }

    static String friendMutationMessage(SamvaadApiException e, String operation) {
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
            case 403 -> operation + " not allowed for this request.";
            case 404 -> "Friend request not found.";
            case 409 -> "Friend request is no longer pending.";
            default -> operation + " failed (HTTP " + e.statusCode() + ").";
        };
    }

    static String friendListMessage(SamvaadApiException e) {
        if (e.kind() == SamvaadApiException.Kind.SERVER_UNAVAILABLE) {
            return "Cannot reach server.";
        }
        if (e.kind() == SamvaadApiException.Kind.MALFORMED_RESPONSE) {
            return "Malformed server response.";
        }
        if (e.kind() == SamvaadApiException.Kind.AUTHENTICATION_FAILED) {
            return "Session expired. Restart and log in again.";
        }
        return "Could not load friend requests (HTTP " + e.statusCode() + ").";
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

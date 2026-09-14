package com.samvaad.tui.realtime;

import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.api.dto.MessageResponse;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import com.samvaad.tui.ui.MessageHistoryLoader;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the realtime lifecycle for one authenticated run: connect with the
 * session token, subscribe to the opened conversation, send, receive into
 * the store, and minimal reconnect with history catch-up.
 *
 * <p>Holds the access token internally so UI code never sees it. Listener
 * callbacks arrive on STOMP threads and only touch the synchronized
 * store; user-visible notices are drained by the UI thread.
 */
public final class RealtimeManager {

    static final int HISTORY_LIMIT = 20;
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    private final RealtimeClient client;
    private final String serverUrl;
    private final String accessToken;
    private final ConversationStore store;
    private final MessageHistoryLoader historyLoader;
    private final long reconnectDelayMs;
    private final int maxReconnectAttempts;
    private final AtomicReference<String> notice = new AtomicReference<>();

    private UUID currentConversation;
    private UUID subscribedConversation;
    private boolean reconnecting;
    private boolean shutDown;

    public RealtimeManager(RealtimeClient client, String serverUrl, String accessToken,
            ConversationStore store, MessageHistoryLoader historyLoader) {
        this(client, serverUrl, accessToken, store, historyLoader,
                RECONNECT_DELAY_MS, MAX_RECONNECT_ATTEMPTS);
    }

    RealtimeManager(RealtimeClient client, String serverUrl, String accessToken,
            ConversationStore store, MessageHistoryLoader historyLoader,
            long reconnectDelayMs, int maxReconnectAttempts) {
        this.client = client;
        this.serverUrl = serverUrl;
        this.accessToken = accessToken;
        this.store = store;
        this.historyLoader = historyLoader;
        this.reconnectDelayMs = reconnectDelayMs;
        this.maxReconnectAttempts = maxReconnectAttempts;
    }

    /**
     * Opens the WebSocket and STOMP session. Throws on timeout or
     * authentication failure; the caller decides whether to degrade.
     */
    public synchronized void connect() {
        ensureRunning();
        client.connect(RealtimeClient.toWsUrl(serverUrl), accessToken, new StoreListener());
    }

    /**
     * Makes the given conversation the subscribed one, replacing any
     * previous subscription. No-op when already subscribed. Safe to call
     * every UI frame; subscription failures become UI notices.
     */
    public synchronized void switchTo(UUID conversationId) {
        currentConversation = conversationId;
        if (conversationId == null
                || conversationId.equals(subscribedConversation)
                || !client.isConnected()) {
            return;
        }
        try {
            client.subscribe(conversationId);
            subscribedConversation = conversationId;
        } catch (RealtimeException e) {
            notice.set("Subscription failed.");
        }
    }

    /**
     * Sends one message with a fresh idempotency key and returns that key
     * so the caller can reconcile the authoritative broadcast when it
     * arrives. Returns after the frame is handed to the transport;
     * persistence is confirmed only by the later broadcast. Never retries
     * automatically.
     */
    public UUID send(UUID conversationId, String content) {
        UUID requestId = UUID.randomUUID();
        client.send(conversationId, content, requestId);
        return requestId;
    }

    /**
     * Best-effort teardown. After this the manager accepts no more work.
     */
    public synchronized void disconnect() {
        shutDown = true;
        subscribedConversation = null;
        try {
            client.disconnect();
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }

    /**
     * Drains the latest user-visible notice, if any.
     */
    public String takeNotice() {
        return notice.getAndSet(null);
    }

    private synchronized void ensureRunning() {
        if (shutDown) {
            throw new RealtimeException("Realtime is shut down.");
        }
    }

    private synchronized void startReconnect() {
        if (shutDown || reconnecting) {
            return;
        }
        reconnecting = true;
        Thread worker = new Thread(this::reconnectLoop, "samvaad-realtime-reconnect");
        worker.setDaemon(true);
        worker.start();
    }

    private void reconnectLoop() {
        try {
            for (int attempt = 0; attempt < maxReconnectAttempts; attempt++) {
                sleepQuietly(reconnectDelayMs);
                synchronized (this) {
                    if (shutDown) {
                        return;
                    }
                }
                try {
                    client.connect(RealtimeClient.toWsUrl(serverUrl), accessToken,
                            new StoreListener());
                    UUID conversation;
                    synchronized (this) {
                        subscribedConversation = null;
                        conversation = currentConversation;
                    }
                    if (conversation != null) {
                        switchTo(conversation);
                        catchUp(conversation);
                    }
                    notice.set("Reconnected.");
                    return;
                } catch (RealtimeException | SamvaadApiException e) {
                    // Retry until attempts run out; never retry an ambiguous SEND.
                }
            }
            notice.set("Realtime unavailable.");
        } finally {
            synchronized (this) {
                reconnecting = false;
            }
        }
    }

    private void catchUp(UUID conversationId) {
        try {
            List<MessageEntry> fresh = historyLoader.load(
                    conversationId, store.highestLoadedSequence(conversationId), HISTORY_LIMIT);
            store.mergeMessages(conversationId, fresh);
        } catch (SamvaadApiException e) {
            if (e.kind() == SamvaadApiException.Kind.SERVER_UNAVAILABLE) {
                notice.set("Cannot reach server.");
            } else if (e.kind() == SamvaadApiException.Kind.AUTHENTICATION_FAILED) {
                notice.set("Session expired. Restart and log in again.");
            } else {
                notice.set("History catch-up failed.");
            }
        } catch (RuntimeException e) {
            notice.set("History catch-up failed.");
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class StoreListener implements RealtimeListener {
        @Override
        public void onMessage(MessageResponse message) {
            if (message == null || message.conversationId() == null) {
                return;
            }
            store.mergeMessages(message.conversationId(), List.of(new MessageEntry(
                    message.messageId(),
                    message.senderUserId(),
                    message.sequenceNumber(),
                    message.content(),
                    message.serverTimestamp(),
                    message.requestId())));
            if (message.sequenceNumber() > 0) {
                store.updateHighWater(message.conversationId(), message.sequenceNumber());
            }
        }

        @Override
        public void onError(String message) {
            notice.set(message);
        }

        @Override
        public void onConnectionLost() {
            synchronized (RealtimeManager.this) {
                subscribedConversation = null;
            }
            notice.set("Connection lost. Reconnecting...");
            startReconnect();
        }
    }
}

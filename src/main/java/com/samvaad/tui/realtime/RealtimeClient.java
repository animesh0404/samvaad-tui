package com.samvaad.tui.realtime;

import java.util.UUID;

/**
 * Thin seam over the STOMP transport so connection management stays
 * unit-testable without a real socket. Implementations never retry;
 * reconnect policy belongs to the caller.
 */
public interface RealtimeClient {

    /**
     * Derives the raw WebSocket URL from the configured server base URL.
     */
    static String toWsUrl(String serverUrl) {
        String base = serverUrl.endsWith("/")
                ? serverUrl.substring(0, serverUrl.length() - 1)
                : serverUrl;
        if (base.regionMatches(true, 0, "https://", 0, 8)) {
            return "wss://" + base.substring(8) + "/ws";
        }
        if (base.regionMatches(true, 0, "http://", 0, 7)) {
            return "ws://" + base.substring(7) + "/ws";
        }
        throw new RealtimeException("Invalid server URL '" + serverUrl + "'.");
    }

    /**
     * Opens the WebSocket and STOMP-CONNECTs with the access token,
     * blocking briefly. Throws on timeout or authentication failure.
     */
    void connect(String wsUrl, String accessToken, RealtimeListener listener);

    /**
     * Subscribes to one conversation, replacing any previous subscription.
     * Repeated calls for the same conversation are no-ops.
     */
    void subscribe(UUID conversationId);

    /**
     * Sends one chat message. Returns the requestId handed to the transport
     * after handing the frame over; there is no SEND receipt. Throws only
     * when the frame cannot be handed over synchronously.
     */
    UUID send(UUID conversationId, String content, UUID requestId);

    /**
     * Tears down subscription and connection and stops client threads.
     * Idempotent and best-effort.
     */
    void disconnect();

    boolean isConnected();
}

package com.samvaad.tui.realtime;

import com.samvaad.tui.api.dto.MessageResponse;

/**
 * Callbacks from the realtime transport. Invoked on STOMP network threads;
 * implementations must marshal state changes instead of touching the UI.
 */
public interface RealtimeListener {

    /**
     * Authoritative broadcast message received on a subscription.
     */
    void onMessage(MessageResponse message);

    /**
     * Concise, user-safe error surfaced by the transport (ERROR frame or
     * session failure detail).
     */
    void onError(String message);

    /**
     * The transport dropped unexpectedly. The manager decides whether to
     * reconnect; the client itself never retries.
     */
    void onConnectionLost();
}

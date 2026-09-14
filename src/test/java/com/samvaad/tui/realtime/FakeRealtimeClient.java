package com.samvaad.tui.realtime;

import com.samvaad.tui.api.dto.MessageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test double for {@link RealtimeClient}. Records calls and lets tests
 * inject incoming broadcasts, errors, and connection drops.
 */
public final class FakeRealtimeClient implements RealtimeClient {

    public record Connect(String wsUrl, String accessToken) {
    }

    public record Send(UUID conversationId, String content, UUID requestId) {
    }

    private final List<Connect> connects = new ArrayList<>();
    private final List<UUID> subscribes = new ArrayList<>();
    private final List<Send> sends = new ArrayList<>();
    private int disconnects;
    private boolean connected;
    private RealtimeListener listener;
    private RuntimeException connectFailure;
    private RuntimeException sendFailure;

    public List<Connect> connects() {
        return List.copyOf(connects);
    }

    public List<UUID> subscribes() {
        return List.copyOf(subscribes);
    }

    public List<Send> sends() {
        return List.copyOf(sends);
    }

    public int disconnects() {
        return disconnects;
    }

    public void failConnectWith(RuntimeException failure) {
        this.connectFailure = failure;
    }

    public void failSendWith(RuntimeException failure) {
        this.sendFailure = failure;
    }

    public void deliver(MessageResponse message) {
        if (listener != null) {
            listener.onMessage(message);
        }
    }

    public void raiseError(String message) {
        if (listener != null) {
            listener.onError(message);
        }
    }

    public void dropConnection() {
        connected = false;
        if (listener != null) {
            listener.onConnectionLost();
        }
    }

    @Override
    public void connect(String wsUrl, String accessToken, RealtimeListener listener) {
        connects.add(new Connect(wsUrl, accessToken));
        if (connectFailure != null) {
            throw connectFailure;
        }
        this.listener = listener;
        connected = true;
    }

    @Override
    public void subscribe(UUID conversationId) {
        if (!connected) {
            throw new RealtimeException("Realtime is not connected.");
        }
        subscribes.add(conversationId);
    }

    @Override
    public UUID send(UUID conversationId, String content, UUID requestId) {
        if (!connected) {
            throw new RealtimeException("Realtime is not connected.");
        }
        if (sendFailure != null) {
            throw sendFailure;
        }
        sends.add(new Send(conversationId, content, requestId));
        return requestId;
    }

    @Override
    public void disconnect() {
        disconnects++;
        connected = false;
        listener = null;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }
}

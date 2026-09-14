package com.samvaad.tui.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.samvaad.tui.api.dto.MessageResponse;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * {@link RealtimeClient} over Spring's STOMP client with a plain
 * WebSocket transport (no SockJS, per the server contract).
 *
 * <p>Uses the Jackson 2 message converter so broadcast timestamps parse
 * with the same JavaTime handling as the HTTP layer.
 *
 * <p>Limitation, by contract: server application errors are answered on
 * Spring's user-error destination, whose exact wiring is not part of the
 * verified server contract, so this client does not subscribe to it.
 * Transport failures and STOMP ERROR frames surface through
 * {@link RealtimeListener#onError} / {@link RealtimeListener#onConnectionLost}.
 */
public final class SpringRealtimeClient implements RealtimeClient {

    static final String SEND_DESTINATION = "/app/chat.send";
    static final String TOPIC_PREFIX = "/topic/conversations/";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    private WebSocketStompClient client;
    private StompSession session;
    private RealtimeListener listener;
    private UUID subscribedConversation;
    private StompSession.Subscription subscription;

    @Override
    public synchronized void connect(String wsUrl, String accessToken, RealtimeListener listener) {
        disconnect();
        this.listener = listener;
        WebSocketStompClient stompClient = newClient();
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);
        try {
            this.session = stompClient
                    .connectAsync(wsUrl, new WebSocketHttpHeaders(), connectHeaders,
                            new ForwardingHandler())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.client = stompClient;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RealtimeException("Realtime connection interrupted.", e);
        } catch (Exception e) {
            throw new RealtimeException("Realtime connection failed.", e);
        }
    }

    @Override
    public synchronized void subscribe(UUID conversationId) {
        requireConnected();
        if (conversationId.equals(subscribedConversation) && subscription != null) {
            return;
        }
        unsubscribeQuietly();
        subscription = session.subscribe(TOPIC_PREFIX + conversationId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return MessageResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                RealtimeListener current = listener;
                if (current != null && payload instanceof MessageResponse message) {
                    current.onMessage(message);
                }
            }
        });
        subscribedConversation = conversationId;
    }

    @Override
    public synchronized UUID send(UUID conversationId, String content, UUID requestId) {
        requireConnected();
        try {
            session.send(SEND_DESTINATION, new ChatSendPayload(conversationId, content, requestId));
            return requestId;
        } catch (RuntimeException e) {
            throw new RealtimeException("Send failed.", e);
        }
    }

    @Override
    public synchronized void disconnect() {
        unsubscribeQuietly();
        subscribedConversation = null;
        listener = null;
        if (session != null) {
            try {
                session.disconnect();
            } catch (RuntimeException ignored) {
                // Best effort.
            } finally {
                session = null;
            }
        }
        if (client != null) {
            try {
                client.stop();
            } catch (RuntimeException ignored) {
                // Best effort.
            } finally {
                client = null;
            }
        }
    }

    @Override
    public synchronized boolean isConnected() {
        return session != null && session.isConnected();
    }

    private void requireConnected() {
        if (!isConnected()) {
            throw new RealtimeException("Realtime is not connected.");
        }
    }

    private static WebSocketStompClient newClient() {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter(
                new ObjectMapper().registerModule(new JavaTimeModule()));
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(converter);
        return stompClient;
    }

    private void unsubscribeQuietly() {
        if (subscription != null) {
            try {
                subscription.unsubscribe();
            } catch (RuntimeException ignored) {
                // Best effort.
            } finally {
                subscription = null;
            }
        }
    }

    private class ForwardingHandler extends StompSessionHandlerAdapter {
        @Override
        public void handleException(StompSession session, StompCommand command,
                StompHeaders headers, byte[] payload, Throwable exception) {
            RealtimeListener current = listener;
            if (current != null) {
                current.onError(shortError(payload));
            }
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            RealtimeListener current = listener;
            if (current != null) {
                current.onConnectionLost();
            }
        }
    }

    static String shortError(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "Realtime error.";
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        String message = text.length() > 160 ? text.substring(0, 160) : text;
        return message.isBlank() ? "Realtime error." : message;
    }
}

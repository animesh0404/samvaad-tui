package com.samvaad.tui.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpringRealtimeClientTest {

    @Test
    void wsUrlDerivation() {
        assertEquals("ws://localhost:8080/ws",
                RealtimeClient.toWsUrl("http://localhost:8080"));
        assertEquals("ws://localhost:8080/ws",
                RealtimeClient.toWsUrl("http://localhost:8080/"));
        assertEquals("wss://example.com/samvaad/ws",
                RealtimeClient.toWsUrl("https://example.com/samvaad"));
        assertThrows(RealtimeException.class, () -> RealtimeClient.toWsUrl("localhost:8080"));
    }

    @Test
    void sendPayloadHasExactContractFields() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String json = new ObjectMapper().writeValueAsString(
                new ChatSendPayload(conversationId, "Hello", requestId));

        Map<?, ?> parsed = new ObjectMapper().readValue(json, Map.class);
        assertEquals(3, parsed.size());
        assertEquals(conversationId.toString(), parsed.get("conversationId"));
        assertEquals("Hello", parsed.get("content"));
        assertEquals(requestId.toString(), parsed.get("requestId"));
    }

    @Test
    void unconnectedClientRejectsWork() {
        SpringRealtimeClient client = new SpringRealtimeClient();
        try {
            assertFalse(client.isConnected());
            assertThrows(RealtimeException.class,
                    () -> client.subscribe(UUID.randomUUID()));
            assertThrows(RealtimeException.class,
                    () -> client.send(UUID.randomUUID(), "hi", UUID.randomUUID()));
        } finally {
            client.disconnect();
        }
    }

    @Test
    void disconnectIsIdempotent() {
        SpringRealtimeClient client = new SpringRealtimeClient();
        client.disconnect();
        client.disconnect();
        assertFalse(client.isConnected());
    }

    @Test
    void shortErrorIsConcise() {
        assertEquals("Realtime error.", SpringRealtimeClient.shortError(null));
        assertEquals("Realtime error.", SpringRealtimeClient.shortError(new byte[0]));
        assertEquals("boom", SpringRealtimeClient.shortError("boom".getBytes()));
        assertEquals(160, SpringRealtimeClient.shortError(new byte[500]).length());
    }

    @Test
    void destinationsMatchContract() {
        assertEquals("/app/chat.send", SpringRealtimeClient.SEND_DESTINATION);
        assertEquals("/topic/conversations/", SpringRealtimeClient.TOPIC_PREFIX);
    }
}

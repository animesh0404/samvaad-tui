package com.samvaad.tui.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.MessageResponse;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealtimeManagerTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static ConversationStore store() {
        return new ConversationStore(ME, List.of(new ConversationEntry(
                CONVERSATION_ID, OTHER_ID, "bob", 2,
                LocalDateTime.of(2026, 9, 14, 10, 0))));
    }

    private static List<MessageEntry> noHistory() {
        return List.of();
    }

    private static RealtimeManager manager(
            FakeRealtimeClient client, ConversationStore store) {
        return new RealtimeManager(client, "http://localhost:8080", "token", store,
                (id, after, limit) -> noHistory(), 10, 5);
    }

    private static MessageResponse broadcast(UUID messageId, UUID requestId, long sequence) {
        return new MessageResponse(messageId, CONVERSATION_ID, OTHER_ID, sequence, "Hello",
                LocalDateTime.of(2026, 9, 14, 10, 0), requestId);
    }

    @Test
    void switchSubscribesOncePerConversation() {
        FakeRealtimeClient client = new FakeRealtimeClient();
        RealtimeManager manager = manager(client, store());
        manager.connect();

        manager.switchTo(CONVERSATION_ID);
        manager.switchTo(CONVERSATION_ID);

        assertEquals(List.of(CONVERSATION_ID), client.subscribes());
    }

    @Test
    void switchReplacesPreviousSubscription() {
        FakeRealtimeClient client = new FakeRealtimeClient();
        RealtimeManager manager = manager(client, store());
        manager.connect();
        UUID other = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        manager.switchTo(CONVERSATION_ID);
        manager.switchTo(other);

        assertEquals(List.of(CONVERSATION_ID, other), client.subscribes());
    }

    @Test
    void sendUsesExactPayloadWithUniqueRequestIds() {
        FakeRealtimeClient client = new FakeRealtimeClient();
        RealtimeManager manager = manager(client, store());
        manager.connect();

        manager.send(CONVERSATION_ID, "one");
        UUID returned = manager.send(CONVERSATION_ID, "two");

        assertEquals(2, client.sends().size());
        assertEquals(returned, client.sends().get(1).requestId(),
                "send must return the idempotency key for reconciliation");        assertEquals(CONVERSATION_ID, client.sends().get(0).conversationId());
        assertEquals("one", client.sends().get(0).content());
        assertTrue(client.sends().get(0).requestId() != null);
        assertNotEquals(client.sends().get(0).requestId(), client.sends().get(1).requestId());
    }

    @Test
    void sendFailurePropagatesWithoutRetry() {
        FakeRealtimeClient client = new FakeRealtimeClient();
        RealtimeManager manager = manager(client, store());
        manager.connect();
        client.failSendWith(new RealtimeException("Send failed."));

        assertThrows(RealtimeException.class, () -> manager.send(CONVERSATION_ID, "one"));
        assertTrue(client.sends().isEmpty(), "failed send must not be recorded or retried");
    }

    @Test
    void incomingBroadcastMergesIntoStore() {
        FakeRealtimeClient client = new FakeRealtimeClient();
        ConversationStore store = store();
        RealtimeManager manager = manager(client, store);
        manager.connect();
        UUID messageId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        client.deliver(broadcast(messageId, requestId, 3));

        List<MessageEntry> messages = store.messagesOf(CONVERSATION_ID);
        assertEquals(1, messages.size());
        assertEquals(messageId, messages.get(0).messageId());
        assertEquals(3, messages.get(0).sequenceNumber());
        assertEquals(requestId, messages.get(0).requestId());
        assertEquals(OTHER_ID, messages.get(0).senderUserId());
        assertEquals(3, store.highestLoadedSequence(CONVERSATION_ID));
        assertEquals(3, store.conversations().get(0).lastSequenceNumber());
    }

    @Test
    void duplicateBroadcastDoesNotDuplicate() {
        FakeRealtimeClient client = new FakeRealtimeClient();
        ConversationStore store = store();
        RealtimeManager manager = manager(client, store);
        manager.connect();
        UUID messageId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        client.deliver(broadcast(messageId, requestId, 3));
        client.deliver(broadcast(messageId, requestId, 3));

        assertEquals(1, store.messagesOf(CONVERSATION_ID).size());
    }

    @Test
    void reconnectReusesTokenResubscribesAndCatchesUp() throws InterruptedException {
        FakeRealtimeClient client = new FakeRealtimeClient();
        ConversationStore store = store();
        List<String> historyCalls = new ArrayList<>();
        RealtimeManager manager = new RealtimeManager(client, "http://localhost:8080", "token",
                store, (id, after, limit) -> {
                    historyCalls.add(id + ":" + after + ":" + limit);
                    return List.of();
                }, 10, 5);
        manager.connect();
        manager.switchTo(CONVERSATION_ID);
        int connectsBefore = client.connects().size();

        client.dropConnection();
        waitForConnects(client, connectsBefore + 1);

        assertEquals(connectsBefore + 1, client.connects().size());
        assertEquals("ws://localhost:8080/ws", client.connects().get(1).wsUrl());
        assertEquals("token", client.connects().get(1).accessToken());
        assertEquals(List.of(CONVERSATION_ID, CONVERSATION_ID), client.subscribes());
        assertEquals(List.of(CONVERSATION_ID + ":0:20"), historyCalls);
        assertTrue(client.sends().isEmpty(), "no ambiguous send may be retried");
    }

    @Test
    void errorNoticeIsDrained() {
        FakeRealtimeClient client = new FakeRealtimeClient();
        RealtimeManager manager = manager(client, store());
        manager.connect();

        client.raiseError("Realtime error.");
        assertEquals("Realtime error.", manager.takeNotice());
        assertTrue(manager.takeNotice() == null);
    }

    @Test
    void disconnectStopsReconnectAttempts() throws InterruptedException {
        FakeRealtimeClient client = new FakeRealtimeClient();
        ConversationStore store = store();
        RealtimeManager manager = manager(client, store);
        manager.connect();
        manager.disconnect();

        client.dropConnection();
        Thread.sleep(100);

        assertEquals(1, client.connects().size(), "no reconnect after disconnect");
        assertEquals(1, client.disconnects());
    }

    private static void waitForConnects(FakeRealtimeClient client, int want)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (client.connects().size() < want && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(want, client.connects().size(), "reconnect must happen");
    }
}

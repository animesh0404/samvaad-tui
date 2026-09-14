package com.samvaad.tui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationStoreTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHARLIE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ALICE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static ConversationEntry entry(UUID id, String username) {
        return new ConversationEntry(id, UUID.randomUUID(), username, 3,
                LocalDateTime.of(2026, 9, 14, 10, 0));
    }

    @Test
    void preservesServerOrderWithoutResorting() {
        ConversationStore store = new ConversationStore(ME,
                List.of(entry(CHARLIE_ID, "charlie"), entry(ALICE_ID, "alice")));

        List<ConversationEntry> conversations = store.conversations();

        assertEquals(CHARLIE_ID, conversations.get(0).conversationId());
        assertEquals(ALICE_ID, conversations.get(1).conversationId());
    }

    @Test
    void displayNameFallsBackForNullUsername() {
        assertEquals("alice", entry(ALICE_ID, "alice").displayName());
        assertEquals("Unknown user", entry(ALICE_ID, null).displayName());
    }

    @Test
    void emptyStoreHasSaneDefaults() {
        ConversationStore store = new ConversationStore(ME, List.of());

        assertTrue(store.conversations().isEmpty());
        assertEquals(ConversationStore.LoadStatus.NOT_LOADED, store.statusOf(ALICE_ID));
        assertEquals(0, store.highestLoadedSequence(ALICE_ID));
        assertTrue(store.messagesOf(ALICE_ID).isEmpty());
    }

    @Test
    void loadingTransitionsOnce() {
        ConversationStore store = new ConversationStore(ME, List.of(entry(ALICE_ID, "alice")));

        assertTrue(store.markLoading(ALICE_ID));
        assertEquals(ConversationStore.LoadStatus.LOADING, store.statusOf(ALICE_ID));
        assertTrue(!store.markLoading(ALICE_ID), "second load must not start");
    }

    @Test
    void putMessagesTracksHighestLoadedSequence() {
        ConversationStore store = new ConversationStore(ME, List.of(entry(ALICE_ID, "alice")));
        store.markLoading(ALICE_ID);

        store.putMessages(ALICE_ID, List.of(
                new MessageEntry(UUID.randomUUID(), ME, 1, "Hi",
                        LocalDateTime.of(2026, 9, 14, 10, 0), UUID.randomUUID()),
                new MessageEntry(UUID.randomUUID(), ALICE_ID, 2, "Hello",
                        LocalDateTime.of(2026, 9, 14, 10, 1), UUID.randomUUID())));

        assertEquals(ConversationStore.LoadStatus.LOADED, store.statusOf(ALICE_ID));
        assertEquals(2, store.highestLoadedSequence(ALICE_ID));
        assertEquals(2, store.messagesOf(ALICE_ID).size());
        assertEquals("Hi", store.messagesOf(ALICE_ID).get(0).content());
    }

    @Test
    void emptyHistoryIsLoadedWithZeroHighWater() {
        ConversationStore store = new ConversationStore(ME, List.of(entry(ALICE_ID, "alice")));
        store.markLoading(ALICE_ID);
        store.putMessages(ALICE_ID, List.of());

        assertEquals(ConversationStore.LoadStatus.LOADED, store.statusOf(ALICE_ID));
        assertEquals(0, store.highestLoadedSequence(ALICE_ID));
    }

    @Test
    void errorStaysVisibleWithoutAutoRetry() {
        ConversationStore store = new ConversationStore(ME, List.of(entry(ALICE_ID, "alice")));
        store.markLoading(ALICE_ID);
        store.putError(ALICE_ID, "Access denied for this conversation.");

        assertEquals(ConversationStore.LoadStatus.ERROR, store.statusOf(ALICE_ID));
        assertEquals("Access denied for this conversation.", store.errorOf(ALICE_ID));
        assertTrue(!store.markLoading(ALICE_ID), "error must not retrigger loading");
    }

    @Test
    void mergeUnionsByIdInServerSequenceOrder() {
        ConversationStore store = new ConversationStore(ME, List.of(entry(ALICE_ID, "alice")));
        UUID first = UUID.randomUUID();
        store.markLoading(ALICE_ID);
        store.putMessages(ALICE_ID, List.of(
                new MessageEntry(first, ME, 1, "Hi",
                        LocalDateTime.of(2026, 9, 14, 10, 0), UUID.randomUUID())));

        store.mergeMessages(ALICE_ID, List.of(
                new MessageEntry(first, ME, 1, "Hi",
                        LocalDateTime.of(2026, 9, 14, 10, 0), UUID.randomUUID()),
                new MessageEntry(UUID.randomUUID(), ALICE_ID, 2, "Hello",
                        LocalDateTime.of(2026, 9, 14, 10, 1), UUID.randomUUID())));

        List<MessageEntry> messages = store.messagesOf(ALICE_ID);
        assertEquals(2, messages.size(), "duplicate id must not duplicate");
        assertEquals(1, messages.get(0).sequenceNumber());
        assertEquals(2, messages.get(1).sequenceNumber());
        assertEquals(2, store.highestLoadedSequence(ALICE_ID));
    }

    @Test
    void updateHighWaterOnlyMovesForwardFromServerValues() {
        ConversationStore store = new ConversationStore(ME, List.of(entry(ALICE_ID, "alice")));

        store.updateHighWater(ALICE_ID, 7);
        assertEquals(7, store.conversations().get(0).lastSequenceNumber());

        store.updateHighWater(ALICE_ID, 4);
        assertEquals(7, store.conversations().get(0).lastSequenceNumber(),
                "client must never move the high-water mark backwards");

        store.updateHighWater(UUID.randomUUID(), 9);
    }

    @Test
    void containsRequestIdFindsAuthoritativeBroadcast() {
        ConversationStore store = new ConversationStore(ME, List.of(entry(ALICE_ID, "alice")));
        UUID requestId = UUID.randomUUID();
        assertTrue(!store.containsRequestId(requestId));

        store.markLoading(ALICE_ID);
        store.putMessages(ALICE_ID, List.of(new MessageEntry(
                UUID.randomUUID(), ME, 1, "Hi", LocalDateTime.of(2026, 9, 14, 10, 0), requestId)));

        assertTrue(store.containsRequestId(requestId));
        assertTrue(!store.containsRequestId(UUID.randomUUID()));
        assertTrue(!store.containsRequestId(null));
    }

    @Test
    void rejectsNullConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationStore(null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationStore(ME, null));
    }
}

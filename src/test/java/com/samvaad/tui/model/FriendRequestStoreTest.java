package com.samvaad.tui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.FriendRequestResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FriendRequestStoreTest {

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CAROL = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static FriendRequestEntry entry(UUID id, String sender, String recipient) {
        return new FriendRequestEntry(id, ALICE, sender, BOB, recipient,
                FriendRequestStatus.PENDING, LocalDateTime.of(2026, 9, 14, 10, 0), null);
    }

    @Test
    void initialStateIsNotLoaded() {
        FriendRequestStore store = new FriendRequestStore();

        assertEquals(FriendRequestStore.LoadStatus.NOT_LOADED, store.lookupStatus());
        assertEquals(FriendRequestStore.LoadStatus.NOT_LOADED, store.incomingStatus());
        assertEquals(FriendRequestStore.LoadStatus.NOT_LOADED, store.outgoingStatus());
        assertTrue(store.incoming().isEmpty());
        assertTrue(store.outgoing().isEmpty());
        assertNull(store.lookupResult());
    }

    @Test
    void lookupResultLifecycle() {
        FriendRequestStore store = new FriendRequestStore();

        store.markLookupLoading();
        assertEquals(FriendRequestStore.LoadStatus.LOADING, store.lookupStatus());

        UserLookupEntry found = new UserLookupEntry(BOB, "bob");
        store.putLookupResult(found);
        assertEquals(FriendRequestStore.LoadStatus.LOADED, store.lookupStatus());
        assertEquals(found, store.lookupResult());
        assertNull(store.lookupError());

        store.putLookupError("User not found.");
        assertEquals(FriendRequestStore.LoadStatus.ERROR, store.lookupStatus());
        assertEquals("User not found.", store.lookupError());
        assertEquals(found, store.lookupResult(), "failed lookup keeps the previous result");

        store.clearLookup();
        assertEquals(FriendRequestStore.LoadStatus.NOT_LOADED, store.lookupStatus());
        assertNull(store.lookupResult());
    }

    @Test
    void incomingPreservesServerOrder() {
        FriendRequestStore store = new FriendRequestStore();
        FriendRequestEntry first = entry(UUID.randomUUID(), "carol", "alice");
        FriendRequestEntry second = entry(UUID.randomUUID(), "bob", "alice");

        store.putIncoming(List.of(first, second));

        List<FriendRequestEntry> held = store.incoming();
        assertEquals(2, held.size());
        assertEquals(first.requestId(), held.get(0).requestId());
        assertEquals(second.requestId(), held.get(1).requestId());
        assertEquals(FriendRequestStore.LoadStatus.LOADED, store.incomingStatus());
    }

    @Test
    void outgoingRefreshReplacesPreviousEntries() {
        FriendRequestStore store = new FriendRequestStore();
        store.putOutgoing(List.of(entry(UUID.randomUUID(), "alice", "bob")));

        store.markOutgoingLoading();
        assertEquals(FriendRequestStore.LoadStatus.LOADING, store.outgoingStatus());
        assertEquals(1, store.outgoing().size(), "stale entries stay visible while refreshing");

        store.putOutgoing(List.of());
        assertEquals(FriendRequestStore.LoadStatus.LOADED, store.outgoingStatus());
        assertTrue(store.outgoing().isEmpty(), "accepted request leaves the pending list");
    }

    @Test
    void incomingErrorKeepsStateVisible() {
        FriendRequestStore store = new FriendRequestStore();
        store.putIncoming(List.of(entry(UUID.randomUUID(), "carol", "alice")));

        store.putIncomingError("Cannot reach server.");

        assertEquals(FriendRequestStore.LoadStatus.ERROR, store.incomingStatus());
        assertEquals("Cannot reach server.", store.incomingError());
        assertEquals(1, store.incoming().size());
    }

    @Test
    void incomingAndOutgoingAreIndependent() {
        FriendRequestStore store = new FriendRequestStore();
        store.putIncoming(List.of(entry(UUID.randomUUID(), "carol", "alice")));
        store.putOutgoingError("Could not load friend requests (HTTP 500).");

        assertEquals(FriendRequestStore.LoadStatus.LOADED, store.incomingStatus());
        assertEquals(FriendRequestStore.LoadStatus.ERROR, store.outgoingStatus());
        assertEquals(1, store.incoming().size());
        assertTrue(store.outgoing().isEmpty());
    }

    @Test
    void entryMapsServerResponse() {
        FriendRequestResponse response = new FriendRequestResponse(
                UUID.randomUUID(), ALICE, "alice", CAROL, "carol", "ACCEPTED",
                LocalDateTime.of(2026, 9, 14, 10, 0),
                LocalDateTime.of(2026, 9, 14, 11, 0));

        FriendRequestEntry mapped = FriendRequestEntry.from(response);

        assertEquals(response.requestId(), mapped.requestId());
        assertEquals(FriendRequestStatus.ACCEPTED, mapped.status());
        assertEquals("alice", mapped.senderUsername());
        assertEquals("carol", mapped.recipientUsername());
        assertEquals("carol", mapped.otherPartyUsername(ALICE));
        assertEquals("alice", mapped.otherPartyUsername(CAROL));
    }

    @Test
    void unknownStatusRejected() {
        FriendRequestResponse response = new FriendRequestResponse(
                UUID.randomUUID(), ALICE, "alice", BOB, "bob", "GHOST",
                LocalDateTime.of(2026, 9, 14, 10, 0), null);

        assertThrows(IllegalArgumentException.class, () -> FriendRequestEntry.from(response));
    }
}

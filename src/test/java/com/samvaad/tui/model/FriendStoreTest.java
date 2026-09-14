package com.samvaad.tui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.FriendResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FriendStoreTest {

    private static final UUID ANNA_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID MIKE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static FriendEntry entry(UUID id, String username) {
        return new FriendEntry(id, username);
    }

    @Test
    void initialStateIsNotLoaded() {
        FriendStore store = new FriendStore();

        assertEquals(FriendStore.LoadStatus.NOT_LOADED, store.status());
        assertTrue(store.friends().isEmpty());
        assertNull(store.error());
    }

    @Test
    void putFriendsPreservesServerOrder() {
        FriendStore store = new FriendStore();
        store.markLoading();

        store.putFriends(List.of(entry(MIKE_ID, "mike"), entry(ANNA_ID, "anna")));

        List<FriendEntry> held = store.friends();
        assertEquals(2, held.size());
        assertEquals(MIKE_ID, held.get(0).userId(), "server order kept, not re-sorted");
        assertEquals(ANNA_ID, held.get(1).userId());
        assertEquals(FriendStore.LoadStatus.LOADED, store.status());
        assertNull(store.error());
    }

    @Test
    void loadingKeepsStaleEntriesVisible() {
        FriendStore store = new FriendStore();
        store.putFriends(List.of(entry(ANNA_ID, "anna")));

        store.markLoading();

        assertEquals(FriendStore.LoadStatus.LOADING, store.status());
        assertEquals(1, store.friends().size(), "stale entries stay visible while loading");
    }

    @Test
    void errorKeepsStateVisible() {
        FriendStore store = new FriendStore();
        store.putFriends(List.of(entry(ANNA_ID, "anna")));

        store.putError("Cannot reach server.");

        assertEquals(FriendStore.LoadStatus.ERROR, store.status());
        assertEquals("Cannot reach server.", store.error());
        assertEquals(1, store.friends().size());
    }

    @Test
    void emptyListIsLoaded() {
        FriendStore store = new FriendStore();
        store.markLoading();

        store.putFriends(List.of());

        assertEquals(FriendStore.LoadStatus.LOADED, store.status());
        assertTrue(store.friends().isEmpty());
    }

    @Test
    void entryMapsServerResponse() {
        FriendResponse response = new FriendResponse(ANNA_ID, "anna");

        FriendEntry mapped = FriendEntry.from(response);

        assertEquals(ANNA_ID, mapped.userId());
        assertEquals("anna", mapped.username());
    }
}

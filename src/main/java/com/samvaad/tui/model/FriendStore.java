package com.samvaad.tui.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-free, server-backed friends state for the TUI lifetime.
 *
 * <p>Holds the authoritative current friends list exactly as returned
 * by {@code GET /api/friends} (username ascending). Entries are never
 * re-sorted here and are never derived from friend requests,
 * conversations, or message history. All methods are synchronized:
 * refresh workers run off the UI thread while the UI thread reads.
 *
 * <p>Deliberately separate from {@link FriendRequestStore} (pending
 * request workflow) and {@link ConversationStore} (message state).
 */
public final class FriendStore {

    public enum LoadStatus {
        NOT_LOADED,
        LOADING,
        LOADED,
        ERROR
    }

    private List<FriendEntry> friends = new ArrayList<>();
    private LoadStatus status = LoadStatus.NOT_LOADED;
    private String error;

    public synchronized List<FriendEntry> friends() {
        return List.copyOf(friends);
    }

    public synchronized LoadStatus status() {
        return status;
    }

    public synchronized String error() {
        return error;
    }

    /**
     * Marks the friends list refreshing. Existing entries stay visible
     * until the refresh completes or fails.
     */
    public synchronized void markLoading() {
        if (status != LoadStatus.LOADING) {
            status = LoadStatus.LOADING;
        }
        error = null;
    }

    /**
     * Replaces the friends list wholesale, preserving the exact
     * server-provided order.
     */
    public synchronized void putFriends(List<FriendEntry> entries) {
        friends = new ArrayList<>(entries);
        status = LoadStatus.LOADED;
        error = null;
    }

    public synchronized void putError(String message) {
        status = LoadStatus.ERROR;
        error = message;
    }
}

package com.samvaad.tui.model;

/**
 * Server-owned friend-request lifecycle states. The server defines
 * exactly these four values; the client must not invent others.
 */
public enum FriendRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELLED;

    /**
     * Parses the exact server status string.
     *
     * @throws IllegalArgumentException for unknown values
     */
    public static FriendRequestStatus fromServer(String status) {
        if (status == null) {
            throw new IllegalArgumentException("Friend request status must not be null.");
        }
        return valueOf(status);
    }
}

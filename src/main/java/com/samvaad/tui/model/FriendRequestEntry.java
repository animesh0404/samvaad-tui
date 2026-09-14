package com.samvaad.tui.model;

import com.samvaad.tui.api.dto.FriendRequestResponse;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * UI-side view of one friend request, mapped from the server response.
 * Entries stay in the exact server-provided order; never re-sort.
 *
 * @param requestId server-owned request identity
 * @param senderUserId request author
 * @param senderUsername request author display name
 * @param recipientUserId request target
 * @param recipientUsername request target display name
 * @param status server-owned lifecycle state
 * @param createdAt zone-less server timestamp, displayed as-is
 * @param respondedAt zone-less server timestamp, null while pending
 */
public record FriendRequestEntry(
        UUID requestId,
        UUID senderUserId,
        String senderUsername,
        UUID recipientUserId,
        String recipientUsername,
        FriendRequestStatus status,
        LocalDateTime createdAt,
        LocalDateTime respondedAt) {

    /**
     * Maps a server friend-request response to UI state.
     */
    public static FriendRequestEntry from(FriendRequestResponse response) {
        return new FriendRequestEntry(
                response.requestId(),
                response.senderUserId(),
                response.senderUsername(),
                response.recipientUserId(),
                response.recipientUsername(),
                FriendRequestStatus.fromServer(response.status()),
                response.createdAt(),
                response.respondedAt());
    }

    /**
     * Display name of the other party relative to the given user.
     */
    public String otherPartyUsername(UUID currentUserId) {
        if (currentUserId != null && currentUserId.equals(senderUserId)) {
            return recipientUsername;
        }
        return senderUsername;
    }
}

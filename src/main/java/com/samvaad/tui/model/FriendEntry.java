package com.samvaad.tui.model;

import com.samvaad.tui.api.dto.FriendResponse;
import java.util.UUID;

/**
 * UI-side view of one accepted friend, mapped from the server friends
 * response. The server returns only id and username; this entry carries
 * nothing more. Friendship itself is a server determination and is
 * never inferred from requests, conversations, or messages here.
 */
public record FriendEntry(
        UUID userId,
        String username) {

    /**
     * Maps a server friend response to UI state.
     */
    public static FriendEntry from(FriendResponse response) {
        return new FriendEntry(response.userId(), response.username());
    }
}

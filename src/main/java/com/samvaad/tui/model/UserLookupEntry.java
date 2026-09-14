package com.samvaad.tui.model;

import com.samvaad.tui.api.dto.UserLookupResponse;
import java.util.UUID;

/**
 * UI-side view of one exact-username lookup result, mapped from the
 * server lookup response. The server returns only id and username;
 * this entry carries nothing more.
 */
public record UserLookupEntry(
        UUID userId,
        String username) {

    /**
     * Maps a server lookup response to UI state.
     */
    public static UserLookupEntry from(UserLookupResponse response) {
        return new UserLookupEntry(response.userId(), response.username());
    }
}

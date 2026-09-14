package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Verified {@code GET /api/friends} element shape: exactly the friend's
 * id and username in server (username ascending) order. Only users with
 * an ACCEPTED friend request in either direction are returned; the
 * current user is never included.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FriendResponse(
        UUID userId,
        String username) {
}

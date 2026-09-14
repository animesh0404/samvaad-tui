package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Verified {@code /api/friend-requests} element shape. {@code status} is
 * the server string {@code PENDING}, {@code ACCEPTED}, {@code REJECTED},
 * or {@code CANCELLED}. {@code respondedAt} is null while pending.
 * Timestamps are zone-less server local times; never convert zones.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FriendRequestResponse(
        UUID requestId,
        UUID senderUserId,
        String senderUsername,
        UUID recipientUserId,
        String recipientUsername,
        String status,
        LocalDateTime createdAt,
        LocalDateTime respondedAt) {
}

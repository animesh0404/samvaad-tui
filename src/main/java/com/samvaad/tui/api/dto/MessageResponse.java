package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Verified {@code GET /api/conversations/direct/{id}/messages} element shape.
 * Sender identity is a user id only; there is no sender username in this DTO.
 * Timestamps are zone-less server local times; never convert zones.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageResponse(
        UUID messageId,
        UUID conversationId,
        UUID senderUserId,
        long sequenceNumber,
        String content,
        LocalDateTime serverTimestamp,
        UUID requestId) {
}

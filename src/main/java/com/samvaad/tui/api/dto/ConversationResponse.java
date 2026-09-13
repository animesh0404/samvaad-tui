package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Verified {@code GET /api/conversations/direct} element shape.
 * Timestamps are zone-less server local times; never convert zones.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationResponse(
        UUID conversationId,
        UUID otherParticipantUserId,
        String otherParticipantUsername,
        long lastSequenceNumber,
        LocalDateTime updatedAt) {
}

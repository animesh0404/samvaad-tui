package com.samvaad.tui.model;

import com.samvaad.tui.api.dto.MessageResponse;
import java.util.UUID;

/**
 * UI-side view of an authoritatively persisted first message together
 * with its server-owned conversation ID. The message itself merges
 * through the existing authoritative message path; the conversation ID
 * drives list reconciliation and realtime subscription. Neither value
 * is fabricated client-side.
 */
public record FirstMessage(
        MessageEntry message,
        UUID conversationId) {

    /**
     * Maps a server first-message response to UI state.
     */
    public static FirstMessage from(MessageResponse response) {
        return new FirstMessage(
                new MessageEntry(
                        response.messageId(),
                        response.senderUserId(),
                        response.sequenceNumber(),
                        response.content(),
                        response.serverTimestamp(),
                        response.requestId()),
                response.conversationId());
    }
}

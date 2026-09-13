package com.samvaad.tui.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * UI-side view of one conversation, mapped from the server list response.
 * Entries stay in the exact server-provided order; never re-sort.
 *
 * @param conversationId server conversation id
 * @param otherParticipantUserId the other participant's user id
 * @param otherParticipantUsername the other participant's username, null when unknown
 * @param lastSequenceNumber server-provided high-water mark; not a loaded-state claim
 * @param updatedAt zone-less server timestamp, displayed as-is
 */
public record ConversationEntry(
        UUID conversationId,
        UUID otherParticipantUserId,
        String otherParticipantUsername,
        long lastSequenceNumber,
        LocalDateTime updatedAt) {

    /**
     * Sidebar/sender display name with a fallback for unknown usernames.
     */
    public String displayName() {
        return otherParticipantUsername != null ? otherParticipantUsername : "Unknown user";
    }
}

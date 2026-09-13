package com.samvaad.tui.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * UI-side view of one history message, mapped from the server response.
 * Entries stay in the exact server-provided ascending sequence order.
 *
 * @param senderUserId author identity, compared against the session user id
 * @param sequenceNumber server-owned 1-based sequence number
 * @param content message text
 * @param serverTimestamp zone-less server timestamp, displayed as-is
 */
public record MessageEntry(
        UUID senderUserId,
        long sequenceNumber,
        String content,
        LocalDateTime serverTimestamp) {
}

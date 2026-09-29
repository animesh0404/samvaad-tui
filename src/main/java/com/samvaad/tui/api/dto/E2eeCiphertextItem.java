package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One device-addressed ciphertext item, shared by mailbox fetch
 * ({@code GET /api/e2ee/mailbox}) and history reads
 * ({@code GET /api/e2ee/conversations/{id}/messages}). Mailbox rows come
 * from undelivered state; history rows come from durable envelopes — the
 * shape is identical, the source differs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record E2eeCiphertextItem(
        UUID messageId,
        UUID conversationId,
        long sequenceNumber,
        UUID senderUserId,
        UUID senderDeviceId,
        String envelopeType,
        String ciphertext,
        LocalDateTime serverTimestamp) {
}

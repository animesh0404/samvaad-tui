package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ciphertext submit outcome ({@code POST /api/e2ee/messages} response,
 * HTTP 201 when created, 200 on idempotent replay).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmitE2eeMessageResponse(
        UUID messageId,
        UUID conversationId,
        long sequenceNumber,
        LocalDateTime serverTimestamp,
        List<UUID> acceptedRecipientDevices,
        boolean createdNew) {
}

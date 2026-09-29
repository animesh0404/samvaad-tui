package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Sync cursor state ({@code PUT /api/e2ee/sync} and
 * {@code GET /api/e2ee/sync} response): how far the bound device has
 * processed the conversation. Independent of mailbox fetch/ack.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncCursorResponse(UUID conversationId, long throughSequence) {
}

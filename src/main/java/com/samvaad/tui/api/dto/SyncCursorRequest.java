package com.samvaad.tui.api.dto;

import java.util.UUID;

/**
 * Sync cursor advance request ({@code PUT /api/e2ee/sync} body).
 * Monotonic per device and conversation: repeats are safe, backwards
 * moves are rejected by the server (409).
 */
public record SyncCursorRequest(UUID conversationId, long throughSequence) {
}

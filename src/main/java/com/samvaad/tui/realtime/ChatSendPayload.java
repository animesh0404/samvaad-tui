package com.samvaad.tui.realtime;

import java.util.UUID;

/**
 * Verified {@code /app/chat.send} payload. The client supplies only
 * routing and idempotency data; message ID, sequence, and timestamp are
 * server-generated and arrive via the broadcast.
 */
public record ChatSendPayload(UUID conversationId, String content, UUID requestId) {
}

package com.samvaad.tui.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Mailbox acknowledgement request ({@code POST /api/e2ee/mailbox/ack}
 * body). Deletes only the bound device's mailbox pointers; durable
 * history is retained. Idempotent: re-acknowledging removes nothing.
 */
public record AckMailboxRequest(List<UUID> messageIds) {
}

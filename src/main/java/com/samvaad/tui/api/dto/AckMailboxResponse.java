package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mailbox acknowledgement outcome ({@code POST /api/e2ee/mailbox/ack}
 * response): how many of the requested pointers were removed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AckMailboxResponse(int acknowledged) {
}

package com.samvaad.tui.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * One logical encrypted message: a client-generated idempotency key plus
 * one opaque per-recipient-device envelope. The server replays the same
 * {@code messageRequestId} without duplicating, so retries reuse it.
 */
public record SubmitE2eeMessageRequest(
        UUID messageRequestId,
        List<E2eeEnvelopeRequest> envelopes) {
}

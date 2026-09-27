package com.samvaad.tui.api.dto;

import java.util.UUID;

/**
 * One-time-prekey claim request
 * ({@code POST /api/e2ee/devices/{deviceId}/one-time-prekeys/claim} body).
 * The request id is the deterministic claim id owned by
 * {@code SamvaadCryptoService}: replays return the same consumed prekey
 * without consuming another, so a retry must reuse it, never mint a new one.
 */
public record ClaimPrekeyRequest(
        UUID requestId) {
}

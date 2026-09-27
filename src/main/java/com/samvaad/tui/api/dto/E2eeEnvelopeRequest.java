package com.samvaad.tui.api.dto;

import java.util.UUID;

/**
 * One recipient device's ciphertext within a logical message
 * ({@code POST /api/e2ee/messages} envelope element).
 * {@code envelopeType} is {@code PREKEY_INIT} or {@code RATCHET} exactly as
 * returned by the crypto library; {@code ciphertext} is standard Base64 of
 * the opaque Signal bytes. Never carries plaintext.
 */
public record E2eeEnvelopeRequest(
        UUID senderDeviceId,
        UUID recipientDeviceId,
        String envelopeType,
        String ciphertext) {
}

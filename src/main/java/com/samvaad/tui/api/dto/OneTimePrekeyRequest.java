package com.samvaad.tui.api.dto;

/**
 * One one-time prekey public upload entry. The private half stays sealed in
 * the local vault; only {@code publicKey} (standard Base64) is uploaded.
 */
public record OneTimePrekeyRequest(
        int prekeyId,
        String publicKey) {
}

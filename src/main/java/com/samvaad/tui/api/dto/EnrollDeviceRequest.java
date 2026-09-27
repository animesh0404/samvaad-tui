package com.samvaad.tui.api.dto;

/**
 * Enrollment request ({@code POST /api/e2ee/devices}). All key fields are
 * standard-Base64 public bytes; no private material ever crosses this
 * boundary.
 */
public record EnrollDeviceRequest(
        int registrationId,
        String deviceIdentityPublicKey,
        int signedPrekeyId,
        String signedPrekey,
        String signedPrekeySignature,
        int kyberPrekeyId,
        String kyberPrekey,
        String kyberPrekeySignature,
        String clientPlatform,
        String clientName,
        String clientVersion) {
}

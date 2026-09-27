package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Recipient-side entry of the public device directory
 * ({@code GET /api/e2ee/users/{username}/devices} element): the public
 * bundle a sender needs for asynchronous session establishment. Shows only
 * whether a one-time prekey is available, never pool counts or bodies.
 * Key material is standard-Base64 public bytes only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecipientDeviceResponse(
        UUID deviceId,
        int registrationId,
        int signalDeviceId,
        String deviceIdentityPublicKey,
        int signedPrekeyId,
        String signedPrekey,
        String signedPrekeySignature,
        boolean hasAvailableOneTimePrekey,
        Integer kyberPrekeyId,
        String kyberPrekey,
        String kyberPrekeySignature) {
}

package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Owner's view of one enrolled E2EE device ({@code GET /api/e2ee/devices}
 * element, {@code POST /api/e2ee/devices} device payload).
 *
 * <p>Field names mirror the server contract exactly. Key material is
 * standard-Base64 public bytes only. {@code status} is kept as the raw
 * server string ({@code ACTIVE}, {@code PENDING}, {@code REVOKED}) and
 * mapped to the local enrollment state by the enrollment layer, which
 * fails closed on unknown values.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record E2eeDeviceResponse(
        UUID deviceId,
        int registrationId,
        int signalDeviceId,
        String deviceIdentityPublicKey,
        int signedPrekeyId,
        Integer kyberPrekeyId,
        String kyberPrekey,
        String kyberPrekeySignature,
        String clientPlatform,
        String clientName,
        String clientVersion,
        long availablePrekeys,
        LocalDateTime createdAt,
        LocalDateTime lastActiveAt,
        String status) {
}

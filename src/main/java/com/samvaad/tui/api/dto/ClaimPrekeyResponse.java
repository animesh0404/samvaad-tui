package com.samvaad.tui.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Full claimed bundle for one recipient device. Contains at most one
 * consumed one-time prekey; when the pool is empty the prekey is absent and
 * the sender falls back to the signed prekey. Kyber material repeats
 * identically across claims (including replays) and is never consumed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimPrekeyResponse(
        UUID deviceId,
        int registrationId,
        int signalDeviceId,
        String deviceIdentityPublicKey,
        int signedPrekeyId,
        String signedPrekey,
        String signedPrekeySignature,
        ClaimedOneTimePrekey oneTimePrekey,
        Integer kyberPrekeyId,
        String kyberPrekey,
        String kyberPrekeySignature) {

    /**
     * The single consumed one-time prekey, or null on signed-prekey
     * fallback. Public bytes only.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClaimedOneTimePrekey(
            int prekeyId,
            String publicKey) {
    }
}

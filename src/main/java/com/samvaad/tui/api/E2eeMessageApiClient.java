package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.samvaad.tui.api.dto.ClaimPrekeyRequest;
import com.samvaad.tui.api.dto.ClaimPrekeyResponse;
import com.samvaad.tui.api.dto.RecipientDeviceResponse;
import com.samvaad.tui.api.dto.SubmitE2eeMessageRequest;
import com.samvaad.tui.api.dto.SubmitE2eeMessageResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * E2EE messaging endpoints of the Samvaad Server API.
 *
 * <p>Implements the verified ciphertext-transport contract:
 * {@code GET /api/e2ee/users/{username}/devices} (recipient directory),
 * {@code POST /api/e2ee/devices/{deviceId}/one-time-prekeys/claim}, and
 * {@code POST /api/e2ee/messages} (accepts 201 created or 200 idempotent
 * replay). The server receives ciphertext only; this client never sends
 * plaintext message content.
 *
 * <p>Transport only: no key generation, no sessions, no private material.
 * The access token is borrowed per call and never persisted.
 */
public final class E2eeMessageApiClient {

    static final String MESSAGES_PATH = "/api/e2ee/messages";

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public E2eeMessageApiClient(HttpTransport transport) {
        this.transport = transport;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Lists the recipient's active devices in server order.
     *
     * @throws SamvaadApiException on authentication, forbidden (not friends),
     *         unknown user, transport, HTTP, or parse failure
     */
    public List<RecipientDeviceResponse> listRecipientDevices(
            String baseUrl, String accessToken, String username) {
        String path = "/api/e2ee/users/" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "/devices";
        HttpResult result = transport.get(baseUrl, path, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Loading recipient devices failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Loading recipient devices");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Loading recipient devices failed (HTTP " + result.statusCode() + ").");
        }
        List<RecipientDeviceResponse> devices;
        try {
            devices = mapper.readValue(result.body(), new TypeReference<List<RecipientDeviceResponse>>() { });
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Loading recipient devices failed: malformed server response.", e);
        }
        if (devices == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Loading recipient devices failed: malformed server response.");
        }
        for (RecipientDeviceResponse device : devices) {
            if (device == null || device.deviceId() == null
                    || device.signalDeviceId() < 1 || isBlank(device.deviceIdentityPublicKey())) {
                throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                        "Loading recipient devices failed: malformed server response.");
            }
        }
        return devices;
    }

    /**
     * Claims one one-time prekey for a recipient device. The request id is
     * owned by {@code SamvaadCryptoService} (deterministic per send slot):
     * callers forward it verbatim so replays return the same consumed
     * prekey without consuming another.
     *
     * @throws SamvaadApiException on authentication, forbidden, unknown
     *         device, claim conflict, transport, HTTP, or parse failure
     */
    public ClaimPrekeyResponse claimPrekey(
            String baseUrl, String accessToken, UUID recipientDeviceId, UUID claimRequestId) {
        String body;
        try {
            body = mapper.writeValueAsString(new ClaimPrekeyRequest(claimRequestId));
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "Prekey claim failed: cannot build request.", e);
        }
        HttpResult result = transport.post(
                baseUrl, "/api/e2ee/devices/" + recipientDeviceId + "/one-time-prekeys/claim",
                body, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Prekey claim failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Prekey claim");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Prekey claim failed (HTTP " + result.statusCode() + ").");
        }
        ClaimPrekeyResponse response;
        try {
            response = mapper.readValue(result.body(), ClaimPrekeyResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Prekey claim failed: malformed server response.", e);
        }
        if (response == null || response.deviceId() == null
                || isBlank(response.deviceIdentityPublicKey())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Prekey claim failed: malformed server response.");
        }
        return response;
    }

    /**
     * Submits one logical encrypted message. Accepts both 201 (created) and
     * 200 (idempotent replay of the same {@code messageRequestId}).
     *
     * @throws SamvaadApiException on authentication, validation, forbidden,
     *         unknown device, conflict, transport, HTTP, or parse failure
     */
    public SubmitE2eeMessageResponse submitMessage(
            String baseUrl, String accessToken, SubmitE2eeMessageRequest request) {
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "Message submit failed: cannot build request.", e);
        }
        HttpResult result = transport.post(baseUrl, MESSAGES_PATH, body, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Message submit failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Message submit");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Message submit failed (HTTP " + result.statusCode() + ").");
        }
        SubmitE2eeMessageResponse response;
        try {
            response = mapper.readValue(result.body(), SubmitE2eeMessageResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Message submit failed: malformed server response.", e);
        }
        if (response == null || response.messageId() == null || response.conversationId() == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Message submit failed: malformed server response.");
        }
        return response;
    }

    private static void throwIfE2eeError(HttpResult result, String operation) {
        switch (result.statusCode()) {
            case 400 -> throw new SamvaadApiException(SamvaadApiException.Kind.INVALID_REQUEST,
                    400, operation + " failed: invalid request.");
            case 403 -> throw new SamvaadApiException(SamvaadApiException.Kind.FORBIDDEN,
                    403, operation + " failed: forbidden.");
            case 404 -> throw new SamvaadApiException(SamvaadApiException.Kind.NOT_FOUND, 404,
                    operation + " failed: not found.");
            case 409 -> throw new SamvaadApiException(SamvaadApiException.Kind.CONFLICT, 409,
                    operation + " failed: conflicting state.");
            default -> {
            }
        }
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

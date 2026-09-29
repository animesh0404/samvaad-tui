package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.samvaad.tui.api.dto.ClaimPrekeyRequest;
import com.samvaad.tui.api.dto.ClaimPrekeyResponse;
import com.samvaad.tui.api.dto.AckMailboxRequest;
import com.samvaad.tui.api.dto.AckMailboxResponse;
import com.samvaad.tui.api.dto.E2eeCiphertextItem;
import com.samvaad.tui.api.dto.RecipientDeviceResponse;
import com.samvaad.tui.api.dto.SyncCursorRequest;
import com.samvaad.tui.api.dto.SyncCursorResponse;
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
 * {@code POST /api/e2ee/devices/{deviceId}/one-time-prekeys/claim},
 * {@code POST /api/e2ee/messages} (accepts 201 created or 200 idempotent
 * replay), {@code GET /api/e2ee/mailbox} (pending envelopes for the
 * session-bound device), {@code POST /api/e2ee/mailbox/ack},
 * {@code GET /api/e2ee/conversations/{id}/messages} (durable per-device
 * history), and {@code PUT}/{@code GET /api/e2ee/sync} (per-device sync
 * cursor). The server receives ciphertext only; this client never sends
 * plaintext message content. There is no STOMP delivery of ciphertext:
 * inbound envelopes are retrieved through these HTTP endpoints.
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

    /**
     * Fetches pending ciphertext envelopes for the session-bound device,
     * oldest first. Fetching never advances the sync cursor and never
     * removes anything; removal happens only through
     * {@link #acknowledgeMailbox}.
     *
     * @param limit maximum items (server default 50)
     * @throws SamvaadApiException on authentication, transport, HTTP, or
     *         parse failure
     */
    public List<E2eeCiphertextItem> fetchMailbox(String baseUrl, String accessToken, int limit) {
        String path = "/api/e2ee/mailbox?limit=" + limit;
        HttpResult result = transport.get(baseUrl, path, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Loading E2EE mailbox failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Loading E2EE mailbox");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Loading E2EE mailbox failed (HTTP " + result.statusCode() + ").");
        }
        return parseCiphertextItems(result.body(), "Loading E2EE mailbox");
    }

    /**
     * Acknowledges mailbox items after local processing. Deletes only the
     * bound device's mailbox pointers; durable history is retained.
     * Idempotent: re-acknowledging removes nothing and still succeeds.
     *
     * @throws SamvaadApiException on authentication, transport, HTTP, or
     *         parse failure
     */
    public AckMailboxResponse acknowledgeMailbox(
            String baseUrl, String accessToken, List<UUID> messageIds) {
        String body;
        try {
            body = mapper.writeValueAsString(new AckMailboxRequest(messageIds));
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "Acknowledging E2EE mailbox failed: cannot build request.", e);
        }
        HttpResult result = transport.post(baseUrl, "/api/e2ee/mailbox/ack", body, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Acknowledging E2EE mailbox failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Acknowledging E2EE mailbox");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Acknowledging E2EE mailbox failed (HTTP " + result.statusCode() + ").");
        }
        AckMailboxResponse response;
        try {
            response = mapper.readValue(result.body(), AckMailboxResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Acknowledging E2EE mailbox failed: malformed server response.", e);
        }
        if (response == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Acknowledging E2EE mailbox failed: malformed server response.");
        }
        return response;
    }

    /**
     * Reads durable per-device ciphertext history for one conversation.
     * Remains readable after acknowledgement; scoped to envelopes
     * addressed to the session-bound device.
     *
     * @param afterSequence only items above this sequence ({@code >= 0})
     * @param limit maximum items (server default 20)
     * @throws SamvaadApiException on authentication, transport, HTTP, or
     *         parse failure
     */
    public List<E2eeCiphertextItem> fetchHistory(
            String baseUrl, String accessToken, UUID conversationId, long afterSequence, int limit) {
        String path = "/api/e2ee/conversations/" + conversationId
                + "/messages?afterSequence=" + afterSequence + "&limit=" + limit;
        HttpResult result = transport.get(baseUrl, path, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Loading E2EE history failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Loading E2EE history");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Loading E2EE history failed (HTTP " + result.statusCode() + ").");
        }
        return parseCiphertextItems(result.body(), "Loading E2EE history");
    }

    /**
     * Advances the bound device's sync cursor for one conversation.
     * Monotonic: repeats are safe, backwards moves are rejected by the
     * server (409 conflict). Independent of mailbox fetch/ack.
     *
     * @throws SamvaadApiException on authentication, conflict, transport,
     *         HTTP, or parse failure
     */
    public SyncCursorResponse advanceCursor(
            String baseUrl, String accessToken, UUID conversationId, long throughSequence) {
        String body;
        try {
            body = mapper.writeValueAsString(new SyncCursorRequest(conversationId, throughSequence));
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "Advancing E2EE sync cursor failed: cannot build request.", e);
        }
        HttpResult result = transport.put(baseUrl, "/api/e2ee/sync", body, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Advancing E2EE sync cursor failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Advancing E2EE sync cursor");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Advancing E2EE sync cursor failed (HTTP " + result.statusCode() + ").");
        }
        return parseCursor(result.body(), "Advancing E2EE sync cursor");
    }

    /**
     * Reads the bound device's sync cursor for one conversation
     * ({@code throughSequence} 0 when never advanced).
     *
     * @throws SamvaadApiException on authentication, transport, HTTP, or
     *         parse failure
     */
    public SyncCursorResponse readCursor(String baseUrl, String accessToken, UUID conversationId) {
        String path = "/api/e2ee/sync?conversationId=" + conversationId;
        HttpResult result = transport.get(baseUrl, path, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Reading E2EE sync cursor failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Reading E2EE sync cursor");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Reading E2EE sync cursor failed (HTTP " + result.statusCode() + ").");
        }
        return parseCursor(result.body(), "Reading E2EE sync cursor");
    }

    private List<E2eeCiphertextItem> parseCiphertextItems(String body, String operation) {
        List<E2eeCiphertextItem> items;
        try {
            items = mapper.readValue(body, new TypeReference<List<E2eeCiphertextItem>>() { });
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.", e);
        }
        if (items == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.");
        }
        for (E2eeCiphertextItem item : items) {
            if (item == null || item.messageId() == null || item.conversationId() == null
                    || item.senderDeviceId() == null
                    || isBlank(item.envelopeType()) || isBlank(item.ciphertext())) {
                throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                        operation + " failed: malformed server response.");
            }
        }
        return items;
    }

    private SyncCursorResponse parseCursor(String body, String operation) {
        SyncCursorResponse cursor;
        try {
            cursor = mapper.readValue(body, SyncCursorResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.", e);
        }
        if (cursor == null || cursor.conversationId() == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.");
        }
        return cursor;
    }

    private static void throwIfE2eeError(HttpResult result, String operation) {        switch (result.statusCode()) {
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

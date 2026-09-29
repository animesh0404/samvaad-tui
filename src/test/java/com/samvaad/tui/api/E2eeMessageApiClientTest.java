package com.samvaad.tui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.dto.AckMailboxResponse;
import com.samvaad.tui.api.dto.ClaimPrekeyResponse;
import com.samvaad.tui.api.dto.E2eeCiphertextItem;
import com.samvaad.tui.api.dto.SyncCursorResponse;
import com.samvaad.tui.api.dto.RecipientDeviceResponse;
import com.samvaad.tui.api.dto.SubmitE2eeMessageRequest;
import com.samvaad.tui.api.dto.SubmitE2eeMessageResponse;
import com.samvaad.tui.api.dto.E2eeEnvelopeRequest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class E2eeMessageApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "token";
    private static final UUID DEVICE_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String KEY_B64 = Base64.getEncoder().encodeToString(new byte[] {5, 1, 2});

    @Test
    void directoryListsEveryActiveDevice() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + directoryJson(DEVICE_A, true) + ","
                + directoryJson(DEVICE_B, false) + "]");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        List<RecipientDeviceResponse> devices = client.listRecipientDevices(BASE_URL, TOKEN, "bob");

        assertEquals(2, devices.size());
        assertEquals(DEVICE_A, devices.get(0).deviceId());
        assertEquals(3, devices.get(0).signalDeviceId());
        assertTrue(devices.get(0).hasAvailableOneTimePrekey());
        assertEquals(DEVICE_B, devices.get(1).deviceId());
        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals("/api/e2ee/users/bob/devices", call.path());
        assertEquals(TOKEN, call.bearerToken());
    }

    @Test
    void directoryEncodesUsername() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[]");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        client.listRecipientDevices(BASE_URL, TOKEN, "bob smith");

        assertTrue(transport.lastCall().path().startsWith("/api/e2ee/users/"));
        assertTrue(transport.lastCall().path().endsWith("/devices"));
    }

    @Test
    void directoryForbiddenWhenNotFriends() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(403, "denied");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.listRecipientDevices(BASE_URL, TOKEN, "bob"));
        assertEquals(SamvaadApiException.Kind.FORBIDDEN, e.kind());
    }

    @Test
    void directoryUnknownUser() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(404, "gone");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.listRecipientDevices(BASE_URL, TOKEN, "bob"));
        assertEquals(SamvaadApiException.Kind.NOT_FOUND, e.kind());
    }

    @Test
    void directoryMalformedEntry() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[{\"deviceId\":null}]");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        assertThrows(SamvaadApiException.class,
                () -> client.listRecipientDevices(BASE_URL, TOKEN, "bob"));
    }

    @Test
    void claimForwardsServiceOwnedRequestId() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, claimJson(true));
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);
        UUID claimRequestId = UUID.randomUUID();

        ClaimPrekeyResponse response =
                client.claimPrekey(BASE_URL, TOKEN, DEVICE_A, claimRequestId);

        assertEquals(DEVICE_A, response.deviceId());
        assertEquals(77, response.oneTimePrekey().prekeyId());
        Map<?, ?> body = new ObjectMapper().readValue(transport.lastCall().body(), Map.class);
        assertEquals(claimRequestId.toString(), body.get("requestId"));
        assertEquals("/api/e2ee/devices/" + DEVICE_A + "/one-time-prekeys/claim",
                transport.lastCall().path());
    }

    @Test
    void claimSignedFallbackWithoutOneTimePrekey() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, claimJson(false));
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        ClaimPrekeyResponse response =
                client.claimPrekey(BASE_URL, TOKEN, DEVICE_A, UUID.randomUUID());

        assertNull(response.oneTimePrekey());
        assertEquals(DEVICE_A, response.deviceId());
    }

    @Test
    void claimConflictMapsToRetryableKind() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(409, "conflict");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.claimPrekey(BASE_URL, TOKEN, DEVICE_A, UUID.randomUUID()));
        assertEquals(SamvaadApiException.Kind.CONFLICT, e.kind());
    }

    @Test
    void submitAcceptsCreatedAndReplay() {
        for (int status : new int[] {201, 200}) {
            FakeHttpTransport transport = new FakeHttpTransport();
            transport.addJson(status, submitJson(status == 201));
            E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

            SubmitE2eeMessageResponse response = client.submitMessage(BASE_URL, TOKEN,
                    new SubmitE2eeMessageRequest(UUID.randomUUID(),
                            List.of(new E2eeEnvelopeRequest(DEVICE_A, DEVICE_B, "PREKEY_INIT", KEY_B64))));

            assertEquals(status == 201, response.createdNew());
            assertEquals(7L, response.sequenceNumber());
            assertEquals(List.of(DEVICE_B), response.acceptedRecipientDevices());
            assertEquals("/api/e2ee/messages", transport.lastCall().path());
        }
    }

    @Test
    void submitMapsServerErrors() {
        int[] statuses = {400, 403, 404, 409};
        SamvaadApiException.Kind[] kinds = {
                SamvaadApiException.Kind.INVALID_REQUEST,
                SamvaadApiException.Kind.FORBIDDEN,
                SamvaadApiException.Kind.NOT_FOUND,
                SamvaadApiException.Kind.CONFLICT};
        for (int i = 0; i < statuses.length; i++) {
            FakeHttpTransport transport = new FakeHttpTransport();
            transport.addJson(statuses[i], "nope");
            E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

            SamvaadApiException e = assertThrows(SamvaadApiException.class,
                    () -> client.submitMessage(BASE_URL, TOKEN,
                            new SubmitE2eeMessageRequest(UUID.randomUUID(), List.of())));
            assertEquals(kinds[i], e.kind());
        }
    }

    @Test
    void submitMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(201, "{\"messageId\":null}");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        assertThrows(SamvaadApiException.class, () -> client.submitMessage(BASE_URL, TOKEN,
                new SubmitE2eeMessageRequest(UUID.randomUUID(), List.of())));
    }

    @Test
    void mailboxFetchesPendingEnvelopes() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + ciphertextJson(messageId, conversationId, 9L) + "]");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        List<E2eeCiphertextItem> items = client.fetchMailbox(BASE_URL, TOKEN, 50);

        assertEquals(1, items.size());
        assertEquals(messageId, items.get(0).messageId());
        assertEquals(conversationId, items.get(0).conversationId());
        assertEquals(9L, items.get(0).sequenceNumber());
        assertEquals("PREKEY_INIT", items.get(0).envelopeType());
        assertEquals(KEY_B64, items.get(0).ciphertext());
        assertEquals("/api/e2ee/mailbox?limit=50", transport.lastCall().path());
        assertEquals(TOKEN, transport.lastCall().bearerToken());
    }

    @Test
    void mailboxEmptyWhenNothingPending() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[]");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        assertTrue(client.fetchMailbox(BASE_URL, TOKEN, 50).isEmpty());
    }

    @Test
    void mailboxAuthenticationFailed() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "nope");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.fetchMailbox(BASE_URL, TOKEN, 50));
        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
    }

    @Test
    void mailboxMalformedItem() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[{\"messageId\":null}]");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        assertThrows(SamvaadApiException.class, () -> client.fetchMailbox(BASE_URL, TOKEN, 50));
    }

    @Test
    void acknowledgeRemovesMailboxPointers() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"acknowledged\":2}");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        AckMailboxResponse response =
                client.acknowledgeMailbox(BASE_URL, TOKEN, List.of(first, second));

        assertEquals(2, response.acknowledged());
        assertEquals("/api/e2ee/mailbox/ack", transport.lastCall().path());
        Map<?, ?> body = new ObjectMapper().readValue(transport.lastCall().body(), Map.class);
        assertEquals(List.of(first.toString(), second.toString()), body.get("messageIds"));
    }

    @Test
    void acknowledgeIdempotentReplay() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"acknowledged\":0}");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        AckMailboxResponse response =
                client.acknowledgeMailbox(BASE_URL, TOKEN, List.of(UUID.randomUUID()));

        assertEquals(0, response.acknowledged());
    }

    @Test
    void acknowledgeMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "null");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        assertThrows(SamvaadApiException.class, () -> client.acknowledgeMailbox(
                BASE_URL, TOKEN, List.of(UUID.randomUUID())));
    }

    @Test
    void historyReadsDurableEnvelopes() {
        UUID conversationId = UUID.randomUUID();
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200,
                "[" + ciphertextJson(UUID.randomUUID(), conversationId, 4L) + "]");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        List<E2eeCiphertextItem> items =
                client.fetchHistory(BASE_URL, TOKEN, conversationId, 3L, 20);

        assertEquals(1, items.size());
        assertEquals(4L, items.get(0).sequenceNumber());
        assertEquals("/api/e2ee/conversations/" + conversationId
                + "/messages?afterSequence=3&limit=20", transport.lastCall().path());
        assertEquals(TOKEN, transport.lastCall().bearerToken());
    }

    @Test
    void historyUnknownConversation() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(404, "gone");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () -> client.fetchHistory(
                BASE_URL, TOKEN, UUID.randomUUID(), 0L, 20));
        assertEquals(SamvaadApiException.Kind.NOT_FOUND, e.kind());
    }

    @Test
    void advanceCursorSendsMonotonicPosition() throws Exception {
        UUID conversationId = UUID.randomUUID();
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"conversationId\":\"" + conversationId
                + "\",\"throughSequence\":9}");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        SyncCursorResponse cursor = client.advanceCursor(BASE_URL, TOKEN, conversationId, 9L);

        assertEquals(conversationId, cursor.conversationId());
        assertEquals(9L, cursor.throughSequence());
        assertEquals("/api/e2ee/sync", transport.lastCall().path());
        Map<?, ?> body = new ObjectMapper().readValue(transport.lastCall().body(), Map.class);
        assertEquals(conversationId.toString(), body.get("conversationId"));
        assertEquals(9, body.get("throughSequence"));
    }

    @Test
    void advanceCursorBackwardsMoveIsConflict() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(409, "backwards");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () -> client.advanceCursor(
                BASE_URL, TOKEN, UUID.randomUUID(), 2L));
        assertEquals(SamvaadApiException.Kind.CONFLICT, e.kind());
    }

    @Test
    void readCursorReturnsStoredPosition() {
        UUID conversationId = UUID.randomUUID();
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"conversationId\":\"" + conversationId
                + "\",\"throughSequence\":5}");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        SyncCursorResponse cursor = client.readCursor(BASE_URL, TOKEN, conversationId);

        assertEquals(conversationId, cursor.conversationId());
        assertEquals(5L, cursor.throughSequence());
        assertEquals("/api/e2ee/sync?conversationId=" + conversationId,
                transport.lastCall().path());
    }

    @Test
    void readCursorMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"conversationId\":null}");
        E2eeMessageApiClient client = new E2eeMessageApiClient(transport);

        assertThrows(SamvaadApiException.class,
                () -> client.readCursor(BASE_URL, TOKEN, UUID.randomUUID()));
    }

    private static String ciphertextJson(UUID messageId, UUID conversationId, long sequence) {
        return "{\"messageId\":\"" + messageId + "\","
                + "\"conversationId\":\"" + conversationId + "\","
                + "\"sequenceNumber\":" + sequence + ","
                + "\"senderUserId\":\"" + UUID.randomUUID() + "\","
                + "\"senderDeviceId\":\"" + DEVICE_A + "\","
                + "\"envelopeType\":\"PREKEY_INIT\","
                + "\"ciphertext\":\"" + KEY_B64 + "\","
                + "\"serverTimestamp\":\"2026-09-27T10:00:00\"}";
    }

    private static String directoryJson(UUID deviceId, boolean hasOtpk) {
        return "{\"deviceId\":\"" + deviceId + "\",\"registrationId\":11,"
                + "\"signalDeviceId\":3,\"deviceIdentityPublicKey\":\"" + KEY_B64 + "\","
                + "\"signedPrekeyId\":5,\"signedPrekey\":\"" + KEY_B64 + "\","
                + "\"signedPrekeySignature\":\"" + KEY_B64 + "\","
                + "\"hasAvailableOneTimePrekey\":" + hasOtpk + ","
                + "\"kyberPrekeyId\":8,\"kyberPrekey\":\"" + KEY_B64 + "\","
                + "\"kyberPrekeySignature\":\"" + KEY_B64 + "\"}";
    }

    private static String claimJson(boolean withOtpk) {
        return "{\"deviceId\":\"" + DEVICE_A + "\",\"registrationId\":11,"
                + "\"signalDeviceId\":3,\"deviceIdentityPublicKey\":\"" + KEY_B64 + "\","
                + "\"signedPrekeyId\":5,\"signedPrekey\":\"" + KEY_B64 + "\","
                + "\"signedPrekeySignature\":\"" + KEY_B64 + "\","
                + (withOtpk ? "\"oneTimePrekey\":{\"prekeyId\":77,\"publicKey\":\"" + KEY_B64 + "\"}," : "")
                + "\"kyberPrekeyId\":8,\"kyberPrekey\":\"" + KEY_B64 + "\","
                + "\"kyberPrekeySignature\":\"" + KEY_B64 + "\"}";
    }

    private static String submitJson(boolean createdNew) {
        return "{\"messageId\":\"" + UUID.randomUUID() + "\","
                + "\"conversationId\":\"" + UUID.randomUUID() + "\","
                + "\"sequenceNumber\":7,\"serverTimestamp\":\"2026-09-27T10:00:00\","
                + "\"acceptedRecipientDevices\":[\"" + DEVICE_B + "\"],"
                + "\"createdNew\":" + createdNew + "}";
    }
}

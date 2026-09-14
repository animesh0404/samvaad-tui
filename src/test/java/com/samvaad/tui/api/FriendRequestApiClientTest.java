package com.samvaad.tui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.FriendRequestResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FriendRequestApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "access-token";
    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALICE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final String PENDING_JSON = "{"
            + "\"requestId\":\"" + REQUEST_ID + "\","
            + "\"senderUserId\":\"" + ALICE_ID + "\",\"senderUsername\":\"alice\","
            + "\"recipientUserId\":\"" + BOB_ID + "\",\"recipientUsername\":\"bob\","
            + "\"status\":\"PENDING\","
            + "\"createdAt\":\"2026-09-14T10:15:30\",\"respondedAt\":null}";

    private static final String ACCEPTED_JSON = "{"
            + "\"requestId\":\"" + REQUEST_ID + "\","
            + "\"senderUserId\":\"" + ALICE_ID + "\",\"senderUsername\":\"alice\","
            + "\"recipientUserId\":\"" + BOB_ID + "\",\"recipientUsername\":\"bob\","
            + "\"status\":\"ACCEPTED\","
            + "\"createdAt\":\"2026-09-14T10:15:30\",\"respondedAt\":\"2026-09-14T10:16:00\"}";

    @Test
    void sendSendsExactPathAndBody() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(201, PENDING_JSON);

        FriendRequestResponse response =
                new FriendRequestApiClient(transport).sendRequest(BASE_URL, TOKEN, "bob");

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals("/api/friend-requests", call.path());
        assertEquals(TOKEN, call.bearerToken());
        assertTrue(call.body().contains("\"username\""), "body carries the username");
        assertTrue(call.body().contains("bob"), "body carries the username");
        assertEquals(REQUEST_ID, response.requestId());
        assertEquals("PENDING", response.status());
        assertEquals("alice", response.senderUsername());
        assertEquals("bob", response.recipientUsername());
        assertNull(response.respondedAt(), "pending requests have no respondedAt");
    }

    @Test
    void sendBlankUsernameIs400WithoutHttp() {
        FakeHttpTransport transport = new FakeHttpTransport();

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).sendRequest(BASE_URL, TOKEN, "  "));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(400, e.statusCode());
        assertTrue(transport.calls().isEmpty(), "blank input must not reach HTTP");
    }

    @Test
    void sendUnknownUsernameIs404() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(404, "{\"message\":\"User not found: ghost\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).sendRequest(BASE_URL, TOKEN, "ghost"));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(404, e.statusCode());
    }

    @Test
    void sendSelfRequestIs403() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(403, "{\"message\":\"Forbidden\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).sendRequest(BASE_URL, TOKEN, "alice"));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(403, e.statusCode());
    }

    @Test
    void sendDuplicatePendingIs409() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(409, "{\"message\":\"Friend request already pending\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).sendRequest(BASE_URL, TOKEN, "bob"));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(409, e.statusCode());
    }

    @Test
    void sendAlreadyFriendsIs409() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(409, "{\"message\":\"Already friends\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).sendRequest(BASE_URL, TOKEN, "bob"));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(409, e.statusCode());
    }

    @Test
    void listIncomingSendsExactPathInOrder() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + PENDING_JSON + "," + ACCEPTED_JSON + "]");

        List<FriendRequestResponse> incoming =
                new FriendRequestApiClient(transport).listIncoming(BASE_URL, TOKEN);

        assertEquals("/api/friend-requests/incoming", transport.lastCall().path());
        assertEquals(TOKEN, transport.lastCall().bearerToken());
        assertEquals(2, incoming.size());
        assertEquals(REQUEST_ID, incoming.get(0).requestId());
        assertEquals("PENDING", incoming.get(0).status());
        assertEquals("ACCEPTED", incoming.get(1).status());
    }

    @Test
    void listOutgoingSendsExactPath() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + PENDING_JSON + "]");

        List<FriendRequestResponse> outgoing =
                new FriendRequestApiClient(transport).listOutgoing(BASE_URL, TOKEN);

        assertEquals("/api/friend-requests/outgoing", transport.lastCall().path());
        assertEquals(1, outgoing.size());
    }

    @Test
    void acceptSendsExactPath() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, ACCEPTED_JSON);

        FriendRequestResponse response =
                new FriendRequestApiClient(transport).accept(BASE_URL, TOKEN, REQUEST_ID);

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals("/api/friend-requests/" + REQUEST_ID + "/accept", call.path());
        assertEquals(TOKEN, call.bearerToken());
        assertEquals("ACCEPTED", response.status());
    }

    @Test
    void rejectSendsExactPath() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, PENDING_JSON.replace("PENDING", "REJECTED"));

        FriendRequestResponse response =
                new FriendRequestApiClient(transport).reject(BASE_URL, TOKEN, REQUEST_ID);

        assertEquals("/api/friend-requests/" + REQUEST_ID + "/reject", transport.lastCall().path());
        assertEquals("REJECTED", response.status());
    }

    @Test
    void cancelSendsExactPath() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, PENDING_JSON.replace("PENDING", "CANCELLED"));

        FriendRequestResponse response =
                new FriendRequestApiClient(transport).cancel(BASE_URL, TOKEN, REQUEST_ID);

        assertEquals("/api/friend-requests/" + REQUEST_ID + "/cancel", transport.lastCall().path());
        assertEquals("CANCELLED", response.status());
    }

    @Test
    void wrongPartyMutationIs403() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(403, "{\"message\":\"Forbidden\"}");

        SamvaadApiException accept = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).accept(BASE_URL, TOKEN, REQUEST_ID));
        assertEquals(403, accept.statusCode());

        transport.addJson(403, "{\"message\":\"Forbidden\"}");
        SamvaadApiException cancel = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).cancel(BASE_URL, TOKEN, REQUEST_ID));
        assertEquals(403, cancel.statusCode());
    }

    @Test
    void nonexistentRequestIs404() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(404, "{\"message\":\"Friend request not found\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).accept(BASE_URL, TOKEN, REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(404, e.statusCode());
    }

    @Test
    void terminalRequestMutationIs409() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(409, "{\"message\":\"Friend request is no longer pending\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).accept(BASE_URL, TOKEN, REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(409, e.statusCode());
    }

    @Test
    void nullRequestIdIs400WithoutHttp() {
        FakeHttpTransport transport = new FakeHttpTransport();

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).accept(BASE_URL, TOKEN, null));

        assertEquals(400, e.statusCode());
        assertTrue(transport.calls().isEmpty(), "null id must not reach HTTP");
    }

    @Test
    void mutation401IsAuthenticationFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"message\":\"unauthorized\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).listIncoming(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
        assertEquals(401, e.statusCode());
    }

    @Test
    void malformedSingleIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "not-json{{");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(transport).accept(BASE_URL, TOKEN, REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void transportFailurePropagates() {
        HttpTransport failing = new HttpTransport() {
            @Override
            public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
                throw new SamvaadApiException(
                        SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1, "down");
            }

            @Override
            public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
                throw new SamvaadApiException(
                        SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1, "down");
            }
        };

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendRequestApiClient(failing).sendRequest(BASE_URL, TOKEN, "bob"));

        assertEquals(SamvaadApiException.Kind.SERVER_UNAVAILABLE, e.kind());
    }
}

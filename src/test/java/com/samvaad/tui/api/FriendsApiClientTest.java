package com.samvaad.tui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.FriendResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FriendsApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "access-token";

    private static final String FRIENDS_JSON = "["
            + "{\"userId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"username\":\"anna\"},"
            + "{\"userId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\",\"username\":\"mike\"},"
            + "{\"userId\":\"cccccccc-cccc-cccc-cccc-cccccccccccc\",\"username\":\"zara\"}]";

    @Test
    void listSendsExactPathWithToken() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, FRIENDS_JSON);

        new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN);

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals(BASE_URL, call.baseUrl());
        assertEquals("/api/friends", call.path());
        assertEquals(TOKEN, call.bearerToken());
    }

    @Test
    void listParsesResponseInServerOrder() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, FRIENDS_JSON);

        List<FriendResponse> friends = new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN);

        assertEquals(3, friends.size());
        assertEquals("anna", friends.get(0).username());
        assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                friends.get(0).userId());
        assertEquals("mike", friends.get(1).username());
        assertEquals("zara", friends.get(2).username());
    }

    @Test
    void listEmptyArrayParsesToEmpty() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[]");

        assertTrue(new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN).isEmpty());
    }

    @Test
    void list401IsAuthenticationFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"message\":\"unauthorized\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
        assertEquals(401, e.statusCode());
    }

    @Test
    void list403IsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(403, "{\"message\":\"forbidden\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(403, e.statusCode());
    }

    @Test
    void list500IsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(500, "boom");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(500, e.statusCode());
    }

    @Test
    void listMalformedBodyIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "not-json{{");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void listMissingUserIdIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[{\"username\":\"bob\"}]");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void listBlankUsernameIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200,
                "[{\"userId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"username\":\"  \"}]");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendsApiClient(transport).listFriends(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void listTransportFailurePropagates() {
        HttpTransport failing = new HttpTransport() {
            @Override
            public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
                throw new UnsupportedOperationException();
            }

            @Override
            public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
                throw new SamvaadApiException(
                        SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1, "down");
            }
        };

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new FriendsApiClient(failing).listFriends(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.SERVER_UNAVAILABLE, e.kind());
    }
}

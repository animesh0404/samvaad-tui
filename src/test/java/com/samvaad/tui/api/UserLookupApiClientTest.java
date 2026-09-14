package com.samvaad.tui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.UserLookupResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserLookupApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "access-token";
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final String LOOKUP_JSON = "{\"userId\":\""
            + USER_ID + "\",\"username\":\"bob\"}";

    @Test
    void lookupSendsExactPath() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, LOOKUP_JSON);

        UserLookupResponse response =
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "bob");

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals(BASE_URL, call.baseUrl());
        assertEquals("/api/users/lookup?username=bob", call.path());
        assertEquals(TOKEN, call.bearerToken());
        assertEquals(USER_ID, response.userId());
        assertEquals("bob", response.username());
    }

    @Test
    void lookupEncodesQueryParameter() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, LOOKUP_JSON);

        new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "bob smith");

        assertEquals("/api/users/lookup?username=bob+smith", transport.lastCall().path());
    }

    @Test
    void lookupPreservesCaseForServerMatching() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, LOOKUP_JSON);

        UserLookupResponse response =
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "BOB");

        assertEquals("/api/users/lookup?username=BOB", transport.lastCall().path());
        assertEquals("bob", response.username());
    }

    @Test
    void lookupBlankUsernameIs400WithoutHttp() {
        FakeHttpTransport transport = new FakeHttpTransport();

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "   "));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(400, e.statusCode());
        assertTrue(transport.calls().isEmpty(), "blank input must not reach HTTP");
    }

    @Test
    void lookupNullUsernameIs400WithoutHttp() {
        FakeHttpTransport transport = new FakeHttpTransport();

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, null));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(400, e.statusCode());
        assertTrue(transport.calls().isEmpty(), "null input must not reach HTTP");
    }

    @Test
    void lookupUnknownUsernameIs404() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(404, "{\"message\":\"User not found: ghost\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "ghost"));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(404, e.statusCode());
    }

    @Test
    void lookupMissingParamIs400() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(400, "{\"message\":\"Username must not be blank\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "bob"));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(400, e.statusCode());
    }

    @Test
    void lookup401IsAuthenticationFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"message\":\"unauthorized\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "bob"));

        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
        assertEquals(401, e.statusCode());
    }

    @Test
    void lookupMalformedBodyIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "not-json{{");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "bob"));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void lookupMissingUserIdIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"username\":\"bob\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new UserLookupApiClient(transport).lookup(BASE_URL, TOKEN, "bob"));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void lookupTransportFailurePropagates() {
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
                new UserLookupApiClient(failing).lookup(BASE_URL, TOKEN, "bob"));

        assertEquals(SamvaadApiException.Kind.SERVER_UNAVAILABLE, e.kind());
    }
}

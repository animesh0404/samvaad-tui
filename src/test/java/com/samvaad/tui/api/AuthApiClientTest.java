package com.samvaad.tui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.dto.AuthResponse;
import org.junit.jupiter.api.Test;

class AuthApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String AUTH_JSON =
            "{\"accessToken\":\"access-1\",\"refreshToken\":\"refresh-1\",\"expiresIn\":3600,\"sessionId\":\"sid-1\"}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loginSendsExactContractFields() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);

        new AuthApiClient(transport).login(BASE_URL, "alice", "s3cret".toCharArray());

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals(BASE_URL, call.baseUrl());
        assertEquals("/api/auth/login", call.path());
        assertNull(call.bearerToken());
        JsonNode body = mapper.readTree(call.body());
        assertEquals("alice", body.get("identifier").asText());
        assertEquals("s3cret", body.get("password").asText());
        assertTrue(body.has("installationId"), "installationId must be present");
        assertTrue(body.get("installationId").isNull(), "installationId must be null");
        assertEquals("TUI", body.get("clientPlatform").asText());
        assertEquals("samvaad-tui", body.get("clientName").asText());
        assertEquals("0.1.0", body.get("clientVersion").asText());
        assertEquals(6, body.size(), "login body must contain exactly the contract fields");
    }

    @Test
    void loginParsesResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);

        AuthResponse response = new AuthApiClient(transport).login(BASE_URL, "alice", "pw".toCharArray());

        assertEquals("access-1", response.accessToken());
        assertEquals("refresh-1", response.refreshToken());
        assertEquals(3600, response.expiresIn());
        assertEquals("sid-1", response.sessionId());
    }

    @Test
    void login401IsAuthenticationFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"error\":\"unauthorized\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> new AuthApiClient(transport).login(BASE_URL, "alice", "wrong".toCharArray()));

        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
        assertEquals(401, e.statusCode());
    }

    @Test
    void loginServerErrorIsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(500, "boom");

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> new AuthApiClient(transport).login(BASE_URL, "alice", "pw".toCharArray()));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(500, e.statusCode());
    }

    @Test
    void loginMalformedBodyIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "not-json{{");

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> new AuthApiClient(transport).login(BASE_URL, "alice", "pw".toCharArray()));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void loginMissingTokenFieldsIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"accessToken\":\"a\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> new AuthApiClient(transport).login(BASE_URL, "alice", "pw".toCharArray()));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void refreshSendsExactContractFieldsAndParsesSameShape() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200,
                "{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\",\"expiresIn\":1800,\"sessionId\":\"sid-1\"}");

        AuthResponse response = new AuthApiClient(transport).refresh(BASE_URL, "refresh-1");

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals("/api/auth/refresh", call.path());
        assertNull(call.bearerToken());
        JsonNode body = mapper.readTree(call.body());
        assertEquals("refresh-1", body.get("refreshToken").asText());
        assertEquals(1, body.size());
        assertEquals("access-2", response.accessToken());
        assertEquals("refresh-2", response.refreshToken());
        assertEquals(1800, response.expiresIn());
        assertEquals("sid-1", response.sessionId());
    }

    @Test
    void refresh401IsAuthenticationFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> new AuthApiClient(transport).refresh(BASE_URL, "stale"));

        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
    }

    @Test
    void logoutSendsBearerHeaderAndNoBody() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "");

        new AuthApiClient(transport).logout(BASE_URL, "access-1");

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals("/api/auth/logout", call.path());
        assertNull(call.body());
        assertEquals("access-1", call.bearerToken());
    }

    @Test
    void logoutFailureIsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(500, "boom");

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> new AuthApiClient(transport).logout(BASE_URL, "access-1"));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(500, e.statusCode());
    }
}

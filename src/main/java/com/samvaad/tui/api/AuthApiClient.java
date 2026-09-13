package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.dto.AuthResponse;
import com.samvaad.tui.api.dto.LoginRequest;
import com.samvaad.tui.api.dto.RefreshRequest;

/**
 * Authentication endpoints of the Samvaad Server API.
 *
 * <p>Implements only the verified contract: {@code POST /api/auth/login},
 * {@code POST /api/auth/refresh}, {@code POST /api/auth/logout}.
 * The caller owns password lifetime; this client only borrows the
 * {@code char[]} for the duration of {@link #login}.
 */
public final class AuthApiClient {

    static final String LOGIN_PATH = "/api/auth/login";
    static final String REFRESH_PATH = "/api/auth/refresh";
    static final String LOGOUT_PATH = "/api/auth/logout";

    static final String CLIENT_PLATFORM = "TUI";
    static final String CLIENT_NAME = "samvaad-tui";
    static final String CLIENT_VERSION = "0.1.0";

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public AuthApiClient(HttpTransport transport) {
        this.transport = transport;
        this.mapper = new ObjectMapper();
    }

    /**
     * Authenticates with username and password.
     *
     * @param baseUrl normalized server base URL
     * @param username identifier supplied by the user
     * @param password borrowed password chars, cleared by the caller
     * @return the server-issued tokens and session id
     * @throws SamvaadApiException on authentication, transport, HTTP, or parse failure
     */
    public AuthResponse login(String baseUrl, String username, char[] password) {
        String body;
        try {
            body = mapper.writeValueAsString(new LoginRequest(
                    username, new String(password), null, CLIENT_PLATFORM, CLIENT_NAME, CLIENT_VERSION));
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "Failed to build login request.", e);
        }
        HttpResult result = transport.post(baseUrl, LOGIN_PATH, body, null);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Authentication failed. Check your username and password.");
        }
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Login failed (HTTP " + result.statusCode() + ").");
        }
        return parseAuthResponse(result.body(), "Login");
    }

    /**
     * Exchanges a refresh token for a new token pair.
     *
     * @param baseUrl normalized server base URL
     * @param refreshToken server-issued refresh token from the latest auth response
     * @return the new server-issued tokens and session id
     * @throws SamvaadApiException on transport, HTTP, or parse failure
     */
    public AuthResponse refresh(String baseUrl, String refreshToken) {
        String body;
        try {
            body = mapper.writeValueAsString(new RefreshRequest(refreshToken));
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "Failed to build refresh request.", e);
        }
        HttpResult result = transport.post(baseUrl, REFRESH_PATH, body, null);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Session refresh failed. Please log in again.");
        }
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Refresh failed (HTTP " + result.statusCode() + ").");
        }
        return parseAuthResponse(result.body(), "Refresh");
    }

    /**
     * Revokes the authenticated persisted session on the server.
     *
     * @param baseUrl normalized server base URL
     * @param accessToken current access token
     * @throws SamvaadApiException when revocation fails
     */
    public void logout(String baseUrl, String accessToken) {
        HttpResult result = transport.post(baseUrl, LOGOUT_PATH, null, accessToken);
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Logout failed (HTTP " + result.statusCode() + ").");
        }
    }

    private AuthResponse parseAuthResponse(String body, String operation) {
        AuthResponse response;
        try {
            response = mapper.readValue(body, AuthResponse.class);
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.", e);
        }
        if (response == null
                || isBlank(response.accessToken())
                || isBlank(response.refreshToken())
                || isBlank(response.sessionId())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.");
        }
        return response;
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

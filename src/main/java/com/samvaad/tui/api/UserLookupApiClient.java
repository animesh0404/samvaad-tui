package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.dto.UserLookupResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Exact-username lookup endpoint of the Samvaad Server API.
 *
 * <p>Implements only the verified contract:
 * {@code GET /api/users/lookup?username={username}}.
 * The server matches exactly (case-insensitively, trimmed) and returns
 * a single {@link UserLookupResponse}; there is no prefix/substring
 * search and no pagination.
 */
public final class UserLookupApiClient {

    static final String LOOKUP_PATH = "/api/users/lookup";

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public UserLookupApiClient(HttpTransport transport) {
        this.transport = transport;
        this.mapper = new ObjectMapper();
    }

    /**
     * Looks up one user by exact username.
     *
     * @throws SamvaadApiException with status 400 for blank usernames,
     *     401 for expired sessions, 404 for unknown usernames, or the
     *     server status for other HTTP failures
     */
    public UserLookupResponse lookup(String baseUrl, String accessToken, String username) {
        if (username == null || username.isBlank()) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, 400,
                    "Username must not be blank.");
        }
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        HttpResult result = transport.get(baseUrl, LOOKUP_PATH + "?username=" + encoded, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Authentication failed. Please log in again.");
        }
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "User lookup failed (HTTP " + result.statusCode() + ").");
        }
        UserLookupResponse response = parse(result.body());
        if (response == null || response.userId() == null || response.username() == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "User lookup failed: malformed server response.");
        }
        return response;
    }

    private UserLookupResponse parse(String body) {
        try {
            return mapper.readValue(body, UserLookupResponse.class);
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "User lookup failed: malformed server response.", e);
        }
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }
}

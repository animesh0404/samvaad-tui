package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.dto.FriendResponse;
import java.util.List;

/**
 * Friends-list endpoint of the Samvaad Server API.
 *
 * <p>Implements only the verified contract:
 * {@code GET /api/friends}. The server returns the authenticated
 * user's accepted friends (either direction of the accepted request)
 * ordered by username ascending. The client must preserve that order
 * and must not derive friendship from any other state.
 */
public final class FriendsApiClient {

    static final String LIST_PATH = "/api/friends";

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public FriendsApiClient(HttpTransport transport) {
        this.transport = transport;
        this.mapper = new ObjectMapper();
    }

    /**
     * Lists the caller's accepted friends in server order.
     *
     * @throws SamvaadApiException on authentication, transport, HTTP, or parse failure
     */
    public List<FriendResponse> listFriends(String baseUrl, String accessToken) {
        HttpResult result = transport.get(baseUrl, LIST_PATH, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Authentication failed. Please log in again.");
        }
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Loading friends failed (HTTP " + result.statusCode() + ").");
        }
        List<FriendResponse> friends;
        try {
            friends = mapper.readValue(result.body(), new TypeReference<List<FriendResponse>>() { });
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Loading friends failed: malformed server response.", e);
        }
        if (friends == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Loading friends failed: malformed server response.");
        }
        for (FriendResponse friend : friends) {
            if (friend == null || friend.userId() == null || isBlank(friend.username())) {
                throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                        "Loading friends failed: malformed server response.");
            }
        }
        return friends;
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.samvaad.tui.api.dto.FriendRequestResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Friend-request endpoints of the Samvaad Server API.
 *
 * <p>Implements only the verified contract:
 * {@code POST /api/friend-requests},
 * {@code GET /api/friend-requests/incoming},
 * {@code GET /api/friend-requests/outgoing},
 * {@code POST /api/friend-requests/{requestId}/accept},
 * {@code POST /api/friend-requests/{requestId}/reject}, and
 * {@code POST /api/friend-requests/{requestId}/cancel}.
 * There is no friend list, unfriend, or friendship-status endpoint and
 * this client does not invent one. The client never generates request
 * ids or idempotency keys; {@code requestId} values are server-owned.
 */
public final class FriendRequestApiClient {

    static final String BASE_PATH = "/api/friend-requests";

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public FriendRequestApiClient(HttpTransport transport) {
        this.transport = transport;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Sends a friend request to the given exact username.
     */
    public FriendRequestResponse sendRequest(String baseUrl, String accessToken, String username) {
        if (username == null || username.isBlank()) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, 400,
                    "Username must not be blank.");
        }
        String body = writeBody(Map.of("username", username), "Send friend request");
        HttpResult result = transport.post(baseUrl, BASE_PATH, body, accessToken);
        return requireSingle(result, "Send friend request");
    }

    /**
     * Lists the caller's pending incoming requests, newest first as
     * returned by the server. The client must preserve that order.
     */
    public List<FriendRequestResponse> listIncoming(String baseUrl, String accessToken) {
        HttpResult result = transport.get(baseUrl, BASE_PATH + "/incoming", accessToken);
        return requireList(result, "Loading incoming requests");
    }

    /**
     * Lists the caller's pending outgoing requests, newest first as
     * returned by the server. The client must preserve that order.
     */
    public List<FriendRequestResponse> listOutgoing(String baseUrl, String accessToken) {
        HttpResult result = transport.get(baseUrl, BASE_PATH + "/outgoing", accessToken);
        return requireList(result, "Loading outgoing requests");
    }

    /**
     * Accepts the given pending request as its recipient.
     */
    public FriendRequestResponse accept(String baseUrl, String accessToken, UUID requestId) {
        return mutate(baseUrl, accessToken, requestId, "accept", "Accept friend request");
    }

    /**
     * Rejects the given pending request as its recipient.
     */
    public FriendRequestResponse reject(String baseUrl, String accessToken, UUID requestId) {
        return mutate(baseUrl, accessToken, requestId, "reject", "Reject friend request");
    }

    /**
     * Cancels the given pending request as its sender.
     */
    public FriendRequestResponse cancel(String baseUrl, String accessToken, UUID requestId) {
        return mutate(baseUrl, accessToken, requestId, "cancel", "Cancel friend request");
    }

    private FriendRequestResponse mutate(
            String baseUrl, String accessToken, UUID requestId, String action, String operation) {
        if (requestId == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, 400,
                    operation + " failed: missing request id.");
        }
        HttpResult result = transport.post(
                baseUrl, BASE_PATH + "/" + requestId + "/" + action, null, accessToken);
        return requireSingle(result, operation);
    }

    private FriendRequestResponse requireSingle(HttpResult result, String operation) {
        checkStatus(result, operation);
        FriendRequestResponse response;
        try {
            response = mapper.readValue(result.body(), FriendRequestResponse.class);
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.", e);
        }
        if (response == null || response.requestId() == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.");
        }
        return response;
    }

    private List<FriendRequestResponse> requireList(HttpResult result, String operation) {
        checkStatus(result, operation);
        try {
            List<FriendRequestResponse> parsed =
                    mapper.readValue(result.body(), new TypeReference<List<FriendRequestResponse>>() { });
            if (parsed == null) {
                throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                        operation + " failed: malformed server response.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.", e);
        }
    }

    private static void checkStatus(HttpResult result, String operation) {
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Authentication failed. Please log in again.");
        }
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    operation + " failed (HTTP " + result.statusCode() + ").");
        }
    }

    private String writeBody(Map<String, String> body, String operation) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    operation + " failed: cannot build request.", e);
        }
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }
}

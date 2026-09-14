package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.samvaad.tui.api.dto.ConversationResponse;
import com.samvaad.tui.api.dto.MessageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation and message-history endpoints of the Samvaad Server API.
 *
 * <p>Implements only the verified contract:
 * {@code GET /api/conversations/direct},
 * {@code GET /api/conversations/direct/{id}/messages}, and
 * {@code POST /api/conversations/direct/messages} for the first message
 * to a friend (which creates the conversation when none exists).
 * There is no single-conversation lookup endpoint and no dedicated
 * conversation-create endpoint; this client does not invent one.
 */
public final class ConversationApiClient {

    static final String LIST_PATH = "/api/conversations/direct";
    static final String MESSAGES_PATH = "/api/conversations/direct/messages";
    static final int DEFAULT_LIMIT = 20;

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public ConversationApiClient(HttpTransport transport) {
        this.transport = transport;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Lists the caller's direct conversations, recent-first as returned
     * by the server. The client must preserve that order.
     */
    public List<ConversationResponse> listDirectConversations(String baseUrl, String accessToken) {
        return listDirectConversations(baseUrl, accessToken, DEFAULT_LIMIT, 0);
    }

    /**
     * Lists the caller's direct conversations with explicit paging.
     */
    public List<ConversationResponse> listDirectConversations(
            String baseUrl, String accessToken, int limit, int offset) {
        HttpResult result = transport.get(
                baseUrl, LIST_PATH + "?limit=" + limit + "&offset=" + offset, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Authentication failed. Please log in again.");
        }
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Loading conversations failed (HTTP " + result.statusCode() + ").");
        }
        List<ConversationResponse> conversations = parseList(result.body(), "Conversations",
                new TypeReference<List<ConversationResponse>>() { });
        for (ConversationResponse conversation : conversations) {
            if (conversation == null || conversation.conversationId() == null) {
                throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                        "Conversations failed: malformed server response.");
            }
        }
        return conversations;
    }

    /**
     * Loads message history with server defaults (from the beginning).
     */
    public List<MessageResponse> getMessageHistory(
            String baseUrl, String accessToken, UUID conversationId) {
        return getMessageHistory(baseUrl, accessToken, conversationId, 0, DEFAULT_LIMIT);
    }

    /**
     * Loads messages with {@code sequenceNumber > afterSequence}, ascending,
     * preserving the exact server order.
     */
    public List<MessageResponse> getMessageHistory(
            String baseUrl, String accessToken, UUID conversationId, long afterSequence, int limit) {
        HttpResult result = transport.get(baseUrl,
                LIST_PATH + "/" + conversationId + "/messages?afterSequence=" + afterSequence
                        + "&limit=" + limit,
                accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Authentication failed. Please log in again.");
        }
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Loading history failed (HTTP " + result.statusCode() + ").");
        }
        List<MessageResponse> messages = parseList(result.body(), "History",
                new TypeReference<List<MessageResponse>>() { });
        for (MessageResponse message : messages) {
            if (message == null
                    || message.messageId() == null
                    || message.senderUserId() == null) {
                throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                        "History failed: malformed server response.");
            }
        }
        return messages;
    }

    /**
     * Sends the first message to a friend by exact username. The server
     * creates the direct conversation when none exists yet and persists
     * the message; a repeated {@code requestId} replays the original
     * persisted message. Message ID, conversation ID, sequence number,
     * and timestamp are server-owned; only the idempotency key is
     * client-generated by the caller.
     *
     * @return the authoritative persisted message, carrying the
     *     authoritative {@code conversationId}
     * @throws SamvaadApiException with status 400 for blank fields,
     *     401 for expired sessions, 403 when messaging is not allowed,
     *     404 for unknown usernames, 409 for request-ID conflicts, or the
     *     server status for other HTTP failures
     */
    public MessageResponse sendFirstMessage(
            String baseUrl, String accessToken, String username, String content, UUID requestId) {
        if (username == null || username.isBlank()) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, 400,
                    "Username must not be blank.");
        }
        if (content == null || content.isBlank()) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, 400,
                    "Message content must not be blank.");
        }
        if (requestId == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, 400,
                    "First message failed: missing request id.");
        }
        String body;
        try {
            body = mapper.writeValueAsString(
                    Map.of("username", username, "content", content,
                            "requestId", requestId.toString()));
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    "First message failed: cannot build request.", e);
        }
        HttpResult result = transport.post(baseUrl, MESSAGES_PATH, body, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Authentication failed. Please log in again.");
        }
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Sending first message failed (HTTP " + result.statusCode() + ").");
        }
        MessageResponse response;
        try {
            response = mapper.readValue(result.body(), MessageResponse.class);
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "First message failed: malformed server response.", e);
        }
        if (response == null
                || response.messageId() == null
                || response.conversationId() == null
                || response.senderUserId() == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "First message failed: malformed server response.");
        }
        return response;
    }

    private <T> List<T> parseList(String body, String operation, TypeReference<List<T>> type) {
        try {
            List<T> parsed = mapper.readValue(body, type);
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

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }
}

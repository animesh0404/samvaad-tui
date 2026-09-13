package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.samvaad.tui.api.dto.ConversationResponse;
import com.samvaad.tui.api.dto.MessageResponse;
import java.util.List;
import java.util.UUID;

/**
 * Conversation and message-history endpoints of the Samvaad Server API.
 *
 * <p>Implements only the verified contract:
 * {@code GET /api/conversations/direct} and
 * {@code GET /api/conversations/direct/{id}/messages}.
 * There is no single-conversation lookup endpoint and sending belongs
 * to a later phase.
 */
public final class ConversationApiClient {

    static final String LIST_PATH = "/api/conversations/direct";
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

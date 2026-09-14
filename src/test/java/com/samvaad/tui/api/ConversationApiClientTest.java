package com.samvaad.tui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.dto.ConversationResponse;
import com.samvaad.tui.api.dto.MessageResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "access-token";
    private static final UUID CONVERSATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final String LIST_JSON = "["
            + "{\"conversationId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
            + "\"otherParticipantUserId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\","
            + "\"otherParticipantUsername\":\"bob\","
            + "\"lastSequenceNumber\":3,"
            + "\"updatedAt\":\"2026-09-14T10:15:30\"},"
            + "{\"conversationId\":\"cccccccc-cccc-cccc-cccc-cccccccccccc\","
            + "\"otherParticipantUserId\":\"dddddddd-dddd-dddd-dddd-dddddddddddd\","
            + "\"otherParticipantUsername\":null,"
            + "\"lastSequenceNumber\":0,"
            + "\"updatedAt\":\"2026-09-13T09:00:00\"}]";

    private static final String HISTORY_JSON = "["
            + "{\"messageId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"conversationId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
            + "\"senderUserId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\","
            + "\"sequenceNumber\":1,\"content\":\"Hello\","
            + "\"serverTimestamp\":\"2026-09-14T10:15:31\","
            + "\"requestId\":\"22222222-2222-2222-2222-222222222222\"},"
            + "{\"messageId\":\"33333333-3333-3333-3333-333333333333\","
            + "\"conversationId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
            + "\"senderUserId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"sequenceNumber\":2,\"content\":\"Hi!\","
            + "\"serverTimestamp\":\"2026-09-14T10:16:00\","
            + "\"requestId\":\"44444444-4444-4444-4444-444444444444\"}]";

    @Test
    void listSendsExactPathAndDefaults() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[]");

        new ConversationApiClient(transport).listDirectConversations(BASE_URL, TOKEN);

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals(BASE_URL, call.baseUrl());
        assertEquals("/api/conversations/direct?limit=20&offset=0", call.path());
        assertEquals(TOKEN, call.bearerToken());
    }

    @Test
    void listForwardsExplicitPaging() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[]");

        new ConversationApiClient(transport).listDirectConversations(BASE_URL, TOKEN, 5, 10);

        assertEquals("/api/conversations/direct?limit=5&offset=10", transport.lastCall().path());
    }

    @Test
    void listParsesResponseInOrder() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, LIST_JSON);

        List<ConversationResponse> conversations =
                new ConversationApiClient(transport).listDirectConversations(BASE_URL, TOKEN);

        assertEquals(2, conversations.size());
        assertEquals(CONVERSATION_ID, conversations.get(0).conversationId());
        assertEquals("bob", conversations.get(0).otherParticipantUsername());
        assertEquals(3, conversations.get(0).lastSequenceNumber());
        assertEquals(LocalDateTime.of(2026, 9, 14, 10, 15, 30), conversations.get(0).updatedAt());
        assertNull(conversations.get(1).otherParticipantUsername(),
                "null participant username must survive parsing");
    }

    @Test
    void listEmptyArrayParsesToEmpty() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[]");

        assertTrue(new ConversationApiClient(transport)
                .listDirectConversations(BASE_URL, TOKEN).isEmpty());
    }

    @Test
    void list401IsAuthenticationFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"message\":\"unauthorized\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport).listDirectConversations(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
        assertEquals(401, e.statusCode());
    }

    @Test
    void list400IsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(400, "{\"message\":\"limit must be between 1 and 100\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport).listDirectConversations(BASE_URL, TOKEN, 0, 0));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(400, e.statusCode());
    }

    @Test
    void listMalformedBodyIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "not-json{{");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport).listDirectConversations(BASE_URL, TOKEN));

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
                new ConversationApiClient(failing).listDirectConversations(BASE_URL, TOKEN));

        assertEquals(SamvaadApiException.Kind.SERVER_UNAVAILABLE, e.kind());
    }

    @Test
    void historySendsExactPathAndDefaults() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[]");

        new ConversationApiClient(transport).getMessageHistory(BASE_URL, TOKEN, CONVERSATION_ID);

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals("/api/conversations/direct/" + CONVERSATION_ID
                + "/messages?afterSequence=0&limit=20", call.path());
        assertEquals(TOKEN, call.bearerToken());
    }

    @Test
    void historyForwardsCursorAndLimit() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[]");

        new ConversationApiClient(transport)
                .getMessageHistory(BASE_URL, TOKEN, CONVERSATION_ID, 2, 5);

        assertEquals("/api/conversations/direct/" + CONVERSATION_ID
                + "/messages?afterSequence=2&limit=5", transport.lastCall().path());
    }

    @Test
    void historyParsesMessagesInOrder() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, HISTORY_JSON);

        List<MessageResponse> messages = new ConversationApiClient(transport)
                .getMessageHistory(BASE_URL, TOKEN, CONVERSATION_ID);

        assertEquals(2, messages.size());
        assertEquals(1, messages.get(0).sequenceNumber());
        assertEquals("Hello", messages.get(0).content());
        assertEquals(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                messages.get(0).senderUserId());
        assertEquals(LocalDateTime.of(2026, 9, 14, 10, 15, 31), messages.get(0).serverTimestamp());
        assertEquals(2, messages.get(1).sequenceNumber());
        assertEquals("Hi!", messages.get(1).content());
    }

    @Test
    void history404IsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(404, "{\"message\":\"not found\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .getMessageHistory(BASE_URL, TOKEN, CONVERSATION_ID));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(404, e.statusCode());
    }

    @Test
    void history403IsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(403, "{\"message\":\"forbidden\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .getMessageHistory(BASE_URL, TOKEN, CONVERSATION_ID));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(403, e.statusCode());
    }

    @Test
    void history401IsAuthenticationFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"message\":\"unauthorized\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .getMessageHistory(BASE_URL, TOKEN, CONVERSATION_ID));

        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
    }

    @Test
    void historyMalformedBodyIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[{\"sequenceNumber\":1}]");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .getMessageHistory(BASE_URL, TOKEN, CONVERSATION_ID));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    private static final UUID FIRST_REQUEST_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String FIRST_MESSAGE_JSON = "{"
            + "\"messageId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"conversationId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
            + "\"senderUserId\":\"99999999-9999-9999-9999-999999999999\","
            + "\"sequenceNumber\":1,\"content\":\"Hey Bob\","
            + "\"serverTimestamp\":\"2026-09-14T10:20:00\","
            + "\"requestId\":\"22222222-2222-2222-2222-222222222222\"}";

    @Test
    void firstMessageSendsExactPathAndBody() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(201, FIRST_MESSAGE_JSON);

        MessageResponse response = new ConversationApiClient(transport)
                .sendFirstMessage(BASE_URL, TOKEN, "bob", "Hey Bob", FIRST_REQUEST_ID);

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals(BASE_URL, call.baseUrl());
        assertEquals("/api/conversations/direct/messages", call.path());
        assertEquals(TOKEN, call.bearerToken());
        assertTrue(call.body().contains("\"username\""), "body carries the username");
        assertTrue(call.body().contains("bob"), "body carries the username");
        assertTrue(call.body().contains("\"content\""), "body carries the content");
        assertTrue(call.body().contains("Hey Bob"), "body carries the content");
        assertTrue(call.body().contains("\"requestId\""), "body carries the request id");
        assertTrue(call.body().contains(FIRST_REQUEST_ID.toString()), "request id is exact");
        assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                response.conversationId());
        assertEquals(1, response.sequenceNumber());
        assertEquals(FIRST_REQUEST_ID, response.requestId());
    }

    @Test
    void firstMessageReplayReturns200Response() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, FIRST_MESSAGE_JSON);

        MessageResponse response = new ConversationApiClient(transport)
                .sendFirstMessage(BASE_URL, TOKEN, "bob", "Hey Bob", FIRST_REQUEST_ID);

        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                response.messageId());
        assertEquals("Hey Bob", response.content());
    }

    @Test
    void firstMessageBlankUsernameMakesNoHttpRequest() {
        FakeHttpTransport transport = new FakeHttpTransport();

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .sendFirstMessage(BASE_URL, TOKEN, "  ", "Hey", FIRST_REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(400, e.statusCode());
        assertTrue(transport.calls().isEmpty(), "blank username must not reach HTTP");
    }

    @Test
    void firstMessageBlankContentMakesNoHttpRequest() {
        FakeHttpTransport transport = new FakeHttpTransport();

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .sendFirstMessage(BASE_URL, TOKEN, "bob", "  ", FIRST_REQUEST_ID));

        assertEquals(400, e.statusCode());
        assertTrue(transport.calls().isEmpty(), "blank content must not reach HTTP");
    }

    @Test
    void firstMessageNullRequestIdMakesNoHttpRequest() {
        FakeHttpTransport transport = new FakeHttpTransport();

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .sendFirstMessage(BASE_URL, TOKEN, "bob", "Hey", null));

        assertEquals(400, e.statusCode());
        assertTrue(transport.calls().isEmpty(), "null request id must not reach HTTP");
    }

    @Test
    void firstMessage401IsAuthenticationFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"message\":\"unauthorized\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .sendFirstMessage(BASE_URL, TOKEN, "bob", "Hey", FIRST_REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
        assertEquals(401, e.statusCode());
    }

    @Test
    void firstMessage403IsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(403, "{\"message\":\"forbidden\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .sendFirstMessage(BASE_URL, TOKEN, "bob", "Hey", FIRST_REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(403, e.statusCode());
    }

    @Test
    void firstMessage404IsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(404, "{\"message\":\"User not found: ghost\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .sendFirstMessage(BASE_URL, TOKEN, "ghost", "Hey", FIRST_REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(404, e.statusCode());
    }

    @Test
    void firstMessage409IsHttpError() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(409, "{\"message\":\"Request ID already used\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .sendFirstMessage(BASE_URL, TOKEN, "bob", "Hey", FIRST_REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.HTTP_ERROR, e.kind());
        assertEquals(409, e.statusCode());
    }

    @Test
    void firstMessageMalformedBodyIsMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(201, "{\"content\":\"Hey\"}");

        SamvaadApiException e = assertThrows(SamvaadApiException.class, () ->
                new ConversationApiClient(transport)
                        .sendFirstMessage(BASE_URL, TOKEN, "bob", "Hey", FIRST_REQUEST_ID));

        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }
}

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
}

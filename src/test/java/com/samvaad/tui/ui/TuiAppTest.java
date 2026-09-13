package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TuiAppTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static ConversationStore store() {
        return new ConversationStore(ME, List.of(new ConversationEntry(
                CONVERSATION_ID, UUID.randomUUID(), "bob", 2,
                LocalDateTime.of(2026, 9, 14, 10, 0))));
    }

    private static SamvaadApiException failure(
            SamvaadApiException.Kind kind, int statusCode) {
        return new SamvaadApiException(kind, statusCode, "boom");
    }

    @Test
    void userMessagesAreConcise() {
        assertEquals("Cannot reach server.",
                TuiApp.userMessage(failure(SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1)));
        assertEquals("Malformed server response.",
                TuiApp.userMessage(failure(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1)));
        assertEquals("Session expired. Restart and log in again.",
                TuiApp.userMessage(failure(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401)));
        assertEquals("Access denied for this conversation.",
                TuiApp.userMessage(failure(SamvaadApiException.Kind.HTTP_ERROR, 403)));
        assertEquals("Conversation unavailable.",
                TuiApp.userMessage(failure(SamvaadApiException.Kind.HTTP_ERROR, 404)));
        assertEquals("Invalid history request.",
                TuiApp.userMessage(failure(SamvaadApiException.Kind.HTTP_ERROR, 400)));
        assertEquals("Could not load history (HTTP 500).",
                TuiApp.userMessage(failure(SamvaadApiException.Kind.HTTP_ERROR, 500)));
    }

    @Test
    void triggerLoadsInitialHistoryFromSequenceZero() throws InterruptedException {
        ConversationStore store = store();
        List<String> calls = new ArrayList<>();
        MessageHistoryLoader loader = (id, after, limit) -> {
            calls.add(id + ":" + after + ":" + limit);
            return List.of(new MessageEntry(ME, 1, "Hi",
                    LocalDateTime.of(2026, 9, 14, 10, 0)));
        };

        TuiApp.triggerHistoryLoad(store, loader, 0);
        waitForStatus(store, ConversationStore.LoadStatus.LOADED);

        assertEquals(List.of(CONVERSATION_ID + ":0:20"), calls);
        assertEquals(1, store.messagesOf(CONVERSATION_ID).size());
        assertEquals(1, store.highestLoadedSequence(CONVERSATION_ID));
    }

    @Test
    void triggerSkipsAlreadyLoadedConversations() {
        ConversationStore store = store();
        store.markLoading(CONVERSATION_ID);
        store.putMessages(CONVERSATION_ID, List.of());

        TuiApp.triggerHistoryLoad(store, (id, after, limit) -> {
            throw new AssertionError("loader must not run again");
        }, 0);
    }

    @Test
    void triggerRecordsErrorWithoutStranding() throws InterruptedException {
        ConversationStore store = store();
        MessageHistoryLoader loader = (id, after, limit) -> {
            throw failure(SamvaadApiException.Kind.HTTP_ERROR, 403);
        };

        TuiApp.triggerHistoryLoad(store, loader, 0);
        waitForStatus(store, ConversationStore.LoadStatus.ERROR);

        assertEquals("Access denied for this conversation.", store.errorOf(CONVERSATION_ID));
    }

    @Test
    void triggerIgnoresEmptyStore() {
        TuiApp.triggerHistoryLoad(new ConversationStore(ME, List.of()),
                (id, after, limit) -> {
                    throw new AssertionError("loader must not run without conversations");
                }, 0);
    }

    private static void waitForStatus(ConversationStore store, ConversationStore.LoadStatus want)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (store.statusOf(CONVERSATION_ID) != want && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(store.statusOf(CONVERSATION_ID) == want, "load must complete, got "
                + store.statusOf(CONVERSATION_ID));
    }
}

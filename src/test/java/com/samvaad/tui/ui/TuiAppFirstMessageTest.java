package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.FirstMessage;
import com.samvaad.tui.model.FriendEntry;
import com.samvaad.tui.model.FriendRequestEntry;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.model.FriendStore;
import com.samvaad.tui.model.MessageEntry;
import com.samvaad.tui.model.UserLookupEntry;
import com.samvaad.tui.realtime.FakeRealtimeClient;
import com.samvaad.tui.realtime.RealtimeManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TuiAppFirstMessageTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID NEW_CONVERSATION_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static ConversationEntry conversation(UUID id, UUID otherId, String username) {
        return new ConversationEntry(id, otherId, username, 1,
                LocalDateTime.of(2026, 9, 14, 10, 0));
    }

    private static MessageEntry message(UUID messageId, long sequence, UUID requestId) {
        return new MessageEntry(messageId, ME, sequence, "Hey",
                LocalDateTime.of(2026, 9, 14, 10, 0), requestId);
    }

    private static SamvaadApiException failure(
            SamvaadApiException.Kind kind, int statusCode) {
        return new SamvaadApiException(kind, statusCode, "boom");
    }

    private static FriendService stubFriends() {
        return new FriendService() {
            @Override
            public UserLookupEntry lookup(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry sendRequest(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FriendRequestEntry> refreshIncoming() {
                return List.of();
            }

            @Override
            public List<FriendRequestEntry> refreshOutgoing() {
                return List.of();
            }

            @Override
            public FriendRequestEntry accept(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry reject(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry cancel(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FriendEntry> refreshFriends() {
                return List.of();
            }

            @Override
            public FirstMessage sendFirstMessage(
                    String username, String content, UUID requestId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static TuiSession session(ConversationStore store, FriendService friends,
            ConversationListLoader conversations) {
        MessageHistoryLoader history = (id, after, limit) -> List.of();
        RealtimeManager realtime = new RealtimeManager(
                new FakeRealtimeClient(), "http://localhost:8080", "token", store, history);
        return new TuiSession("alice", "http://localhost:8080", store, history, realtime,
                new FriendRequestStore(), friends, new FriendStore(), conversations);
    }

    private static void type(TuiState state, String text) {
        for (char c : text.toCharArray()) {
            state.appendToComposer(c);
        }
    }

    @Test
    void resolutionFindsConversationByUserId() {
        List<ConversationEntry> conversations = List.of(
                conversation(CONVERSATION_ID, UUID.randomUUID(), "carol"),
                conversation(UUID.randomUUID(), BOB_ID, "bob"));

        assertEquals(1, TuiApp.findConversationIndexByUser(conversations, BOB_ID));
        assertEquals(-1,
                TuiApp.findConversationIndexByUser(conversations, UUID.randomUUID()));
        assertEquals(-1, TuiApp.findConversationIndexByUser(conversations, null));
        assertEquals(-1, TuiApp.findConversationIndexByUser(List.of(), BOB_ID));
    }

    @Test
    void resolutionIgnoresUsername() {
        List<ConversationEntry> conversations =
                List.of(conversation(CONVERSATION_ID, UUID.randomUUID(), "bob"));

        assertEquals(-1, TuiApp.findConversationIndexByUser(conversations, BOB_ID),
                "username match alone must not resolve");
        assertEquals(0, TuiApp.indexOfConversation(conversations, CONVERSATION_ID));
        assertEquals(-1, TuiApp.indexOfConversation(conversations, UUID.randomUUID()));
    }

    @Test
    void selectFriendOpensExistingConversation() {
        ConversationStore store = new ConversationStore(ME,
                List.of(conversation(UUID.randomUUID(), UUID.randomUUID(), "carol"),
                        conversation(CONVERSATION_ID, BOB_ID, "bob")));
        TuiSession session = session(store, stubFriends(), List::of);
        TuiState state = new TuiState();
        state.showFriendsTab();
        session.friendList().putFriends(List.of(new FriendEntry(BOB_ID, "bob")));
        state.selectFriendDown(1);

        new TuiApp().selectFriend(session, state);

        assertEquals(TuiState.SidebarTab.CONVERSATIONS, state.sidebarTab());
        assertEquals(1, state.selectedIndex());
        assertEquals("Opened bob.", state.status());
        assertNull(state.pendingNewChat(), "no pending state for known conversations");
    }

    @Test
    void selectFriendWithoutConversationStartsPendingNewChat() {
        ConversationStore store = new ConversationStore(ME,
                List.of(conversation(CONVERSATION_ID, UUID.randomUUID(), "carol")));
        TuiSession session = session(store, stubFriends(), List::of);
        TuiState state = new TuiState();
        state.showFriendsTab();
        session.friendList().putFriends(List.of(new FriendEntry(BOB_ID, "bob")));

        new TuiApp().selectFriend(session, state);

        assertNotNull(state.pendingNewChat());
        assertEquals(BOB_ID, state.pendingNewChat().userId(), "authoritative identity kept");
        assertEquals("bob", state.pendingNewChat().username());
        assertEquals(TuiState.Focus.COMPOSER, state.focus(), "composer focused");
        assertEquals(1, store.conversations().size(), "nothing created");
    }

    @Test
    void firstMessageErrorMapping() {
        assertEquals("Cannot reach server.", TuiApp.firstMessageErrorMessage(
                failure(SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1)));
        assertEquals("Malformed server response.", TuiApp.firstMessageErrorMessage(
                failure(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1)));
        assertEquals("Session expired. Restart and log in again.",
                TuiApp.firstMessageErrorMessage(
                        failure(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401)));
        assertEquals("Invalid message.", TuiApp.firstMessageErrorMessage(
                failure(SamvaadApiException.Kind.HTTP_ERROR, 400)));
        assertEquals("Messaging not allowed.", TuiApp.firstMessageErrorMessage(
                failure(SamvaadApiException.Kind.HTTP_ERROR, 403)));
        assertEquals("Friend no longer available.", TuiApp.firstMessageErrorMessage(
                failure(SamvaadApiException.Kind.HTTP_ERROR, 404)));
        assertEquals("Message conflict. Try again.", TuiApp.firstMessageErrorMessage(
                failure(SamvaadApiException.Kind.HTTP_ERROR, 409)));
        assertEquals("Send message failed (HTTP 500).", TuiApp.firstMessageErrorMessage(
                failure(SamvaadApiException.Kind.HTTP_ERROR, 500)));
    }

    @Test
    void friendsListErrorMapping() {
        assertEquals("Session expired. Restart and log in again.", TuiApp.friendsListMessage(
                failure(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401)));
        assertEquals("Could not load friends (HTTP 403).", TuiApp.friendsListMessage(
                failure(SamvaadApiException.Kind.HTTP_ERROR, 403)));
    }

    @Test
    void refreshFriendsRunsOffCallingThread() throws InterruptedException {
        ConversationStore store = new ConversationStore(ME, List.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FriendService friends = new FriendService() {
            @Override
            public UserLookupEntry lookup(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry sendRequest(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FriendRequestEntry> refreshIncoming() {
                return List.of();
            }

            @Override
            public List<FriendRequestEntry> refreshOutgoing() {
                return List.of();
            }

            @Override
            public FriendRequestEntry accept(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry reject(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry cancel(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FriendEntry> refreshFriends() {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }

            @Override
            public FirstMessage sendFirstMessage(
                    String username, String content, UUID requestId) {
                throw new UnsupportedOperationException();
            }
        };
        TuiSession session = session(store, friends, List::of);
        TuiState state = new TuiState();

        new TuiApp().refreshFriends(session, state);

        assertTrue(entered.await(10, TimeUnit.SECONDS), "worker started");
        assertEquals(FriendStore.LoadStatus.LOADING, session.friendList().status());
        release.countDown();
        long end = System.currentTimeMillis() + 10_000;
        while (session.friendList().status() != FriendStore.LoadStatus.LOADED
                && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertEquals(FriendStore.LoadStatus.LOADED, session.friendList().status());
    }

    @Test
    void sendFirstMessageSuccessReconcilesAuthoritatively() throws InterruptedException {
        AtomicReference<UUID> sentRequestId = new AtomicReference<>();
        UUID messageId = UUID.randomUUID();
        ConversationStore store = new ConversationStore(ME,
                List.of(conversation(CONVERSATION_ID, UUID.randomUUID(), "carol")));
        List<ConversationEntry> fresh =
                List.of(conversation(NEW_CONVERSATION_ID, BOB_ID, "bob"),
                        conversation(CONVERSATION_ID, UUID.randomUUID(), "carol"));
        FriendService friends = stubFriends();
        FriendService seam = new FriendService() {
            @Override
            public UserLookupEntry lookup(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry sendRequest(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FriendRequestEntry> refreshIncoming() {
                return List.of();
            }

            @Override
            public List<FriendRequestEntry> refreshOutgoing() {
                return List.of();
            }

            @Override
            public FriendRequestEntry accept(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry reject(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry cancel(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FriendEntry> refreshFriends() {
                return List.of();
            }

            @Override
            public FirstMessage sendFirstMessage(
                    String username, String content, UUID requestId) {
                assertEquals("bob", username);
                assertEquals("Hey Bob", content);
                sentRequestId.set(requestId);
                return new FirstMessage(message(messageId, 1, requestId), NEW_CONVERSATION_ID);
            }
        };
        TuiSession session = session(store, seam, () -> new ArrayList<>(fresh));
        TuiState state = new TuiState();
        state.startNewChat(BOB_ID, "bob");
        state.focusComposer();
        type(state, "Hey Bob");

        new TuiApp().sendFirstMessage(session, state);

        long end = System.currentTimeMillis() + 10_000;
        while (state.pendingNewChat() != null && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertNull(state.pendingNewChat(), "pending cleared on success");
        assertEquals("", state.composer(), "composer cleared on success");
        assertEquals("Message sent to bob.", state.status());
        assertNotNull(sentRequestId.get(), "fresh request id generated");
        assertEquals(2, store.conversations().size(), "authoritative list replaced");
        assertEquals(NEW_CONVERSATION_ID,
                store.conversations().get(state.selectedIndex()).conversationId(),
                "authoritative conversation selected");
        assertEquals(TuiState.SidebarTab.CONVERSATIONS, state.sidebarTab());
        assertEquals(1, store.messagesOf(NEW_CONVERSATION_ID).size(),
                "authoritative message merged once");
        assertEquals(messageId, store.messagesOf(NEW_CONVERSATION_ID).get(0).messageId());
        assertTrue(store.containsRequestId(sentRequestId.get()),
                "no duplicate: merged message carries the request id");
    }

    @Test
    void sendFirstMessageForbiddenClearsPending() throws InterruptedException {
        ConversationStore store = new ConversationStore(ME, List.of());
        FriendService friends = stubFriends();
        FriendService seam = new FriendService() {
            @Override
            public UserLookupEntry lookup(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry sendRequest(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FriendRequestEntry> refreshIncoming() {
                return List.of();
            }

            @Override
            public List<FriendRequestEntry> refreshOutgoing() {
                return List.of();
            }

            @Override
            public FriendRequestEntry accept(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry reject(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FriendRequestEntry cancel(UUID requestId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<FriendEntry> refreshFriends() {
                return List.of();
            }

            @Override
            public FirstMessage sendFirstMessage(
                    String username, String content, UUID requestId) {
                throw failure(SamvaadApiException.Kind.HTTP_ERROR, 403);
            }
        };
        TuiSession session = session(store, seam, List::of);
        TuiState state = new TuiState();
        state.startNewChat(BOB_ID, "bob");
        state.focusComposer();
        type(state, "Hi");

        new TuiApp().sendFirstMessage(session, state);

        long end = System.currentTimeMillis() + 10_000;
        while (!"Messaging not allowed.".equals(state.status())
                && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertEquals("Messaging not allowed.", state.status());
        assertNull(state.pendingNewChat(), "terminal failure leaves the new-chat flow");
        assertEquals("Hi", state.composer(), "composer kept for inspection");
        assertTrue(store.conversations().isEmpty(), "nothing fabricated");
    }
}

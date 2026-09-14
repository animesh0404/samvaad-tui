package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.FirstMessage;
import com.samvaad.tui.model.FriendEntry;
import com.samvaad.tui.model.FriendRequestEntry;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.model.FriendStore;
import com.samvaad.tui.model.UserLookupEntry;
import com.samvaad.tui.realtime.FakeRealtimeClient;
import com.samvaad.tui.realtime.RealtimeManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TuiAppConversationRefreshTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALICE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CAROL_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static ConversationEntry entry(UUID id, UUID otherId, String username) {
        return new ConversationEntry(id, otherId, username, 1,
                LocalDateTime.of(2026, 9, 14, 10, 0));
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

    private static TuiSession session(ConversationStore store,
            ConversationListLoader conversations) {
        MessageHistoryLoader history = (id, after, limit) -> List.of();
        RealtimeManager realtime = new RealtimeManager(
                new FakeRealtimeClient(), "http://localhost:8080", "token", store, history);
        return new TuiSession("alice", "http://localhost:8080", store, history, realtime,
                new FriendRequestStore(), stubFriends(), new FriendStore(), conversations);
    }

    private static void await(java.util.function.BooleanSupplier check, String what)
            throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            if (check.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    void refreshDueOnlyAtInterval() {
        assertTrue(!TuiApp.isRefreshDue(0, 0, 5_000), "no refresh on the first tick at t=0");
        assertTrue(!TuiApp.isRefreshDue(0, 4_999, 5_000), "no refresh before the interval");
        assertTrue(TuiApp.isRefreshDue(0, 5_000, 5_000), "refresh at the interval boundary");
        assertTrue(TuiApp.isRefreshDue(1_000, 6_000, 5_000), "refresh after the interval");
        assertTrue(!TuiApp.isRefreshDue(6_000, 9_000, 5_000), "manual refresh resets the clock");
    }

    @Test
    void autoTickRefreshesOncePerInterval() throws InterruptedException {
        AtomicLong clock = new AtomicLong(6_000);
        AtomicInteger loads = new AtomicInteger();
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, () -> {
            loads.incrementAndGet();
            return List.of();
        });
        TuiApp app = new TuiApp(clock::get);
        TuiState state = new TuiState();

        app.maybeRefreshConversations(session, state);
        await(() -> loads.get() == 1, "first interval refresh");
        joinWorker();

        app.maybeRefreshConversations(session, state);
        app.maybeRefreshConversations(session, state);
        assertEquals(1, loads.get(), "no refresh on every tick");

        clock.set(11_000);
        app.maybeRefreshConversations(session, state);
        await(() -> loads.get() == 2, "second interval refresh");
        assertEquals(2, loads.get());
    }

    @Test
    void overlappingRefreshesPrevented() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        AtomicLong clock = new AtomicLong(6_000);
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, () -> {
            loads.incrementAndGet();
            entered.countDown();
            awaitRelease(release);
            return List.of();
        });
        TuiApp app = new TuiApp(clock::get);
        TuiState state = new TuiState();

        app.maybeRefreshConversations(session, state);
        assertTrue(entered.await(10, TimeUnit.SECONDS), "worker started");

        clock.set(30_000);
        app.maybeRefreshConversations(session, state);
        app.refreshConversations(session, state, true);
        Thread.sleep(100);
        assertEquals(1, loads.get(), "no overlapping refresh while one is in flight");

        release.countDown();
        await(() -> loads.get() == 1 && store.conversations().isEmpty(), "worker finished");
    }

    @Test
    void refreshRunsOffCallingThread() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> workerName = new AtomicReference<>();
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, () -> {
            workerName.set(Thread.currentThread().getName());
            entered.countDown();
            awaitRelease(release);
            return List.of();
        });
        TuiApp app = new TuiApp(() -> 6_000);
        String caller = Thread.currentThread().getName();

        app.refreshConversations(session, new TuiState(), true);

        assertTrue(entered.await(10, TimeUnit.SECONDS), "worker started");
        assertTrue(!caller.equals(workerName.get()), "HTTP never runs on the calling thread");
        assertTrue(workerName.get().startsWith("samvaad-"), "named background worker");
        release.countDown();
        await(() -> !workerAlive("samvaad-conversations-refresh"), "worker finished");
    }

    @Test
    void successfulRefreshPreservesServerOrder() throws InterruptedException {
        ConversationStore store =
                new ConversationStore(ME, List.of(entry(ALICE_ID, ALICE_ID, "alice")));
        List<ConversationEntry> fresh =
                List.of(entry(BOB_ID, BOB_ID, "bob"), entry(ALICE_ID, ALICE_ID, "alice"));
        TuiSession session = session(store, () -> fresh);
        TuiState state = new TuiState();

        new TuiApp().refreshConversations(session, state, true);

        await(() -> store.conversations().size() == 2, "reconciled list");
        assertEquals(BOB_ID, store.conversations().get(0).conversationId());
        assertEquals(ALICE_ID, store.conversations().get(1).conversationId());
    }

    @Test
    void selectionFollowsConversationIdAcrossReorder() throws InterruptedException {
        ConversationStore store = new ConversationStore(ME,
                List.of(entry(ALICE_ID, ALICE_ID, "alice"), entry(BOB_ID, BOB_ID, "bob")));
        List<ConversationEntry> fresh =
                List.of(entry(BOB_ID, BOB_ID, "bob"), entry(ALICE_ID, ALICE_ID, "alice"));
        TuiSession session = session(store, () -> fresh);
        TuiState state = new TuiState();
        state.selectConversation(1);

        new TuiApp().refreshConversations(session, state, true);

        await(() -> store.conversations().get(0).conversationId().equals(BOB_ID), "reordered");
        await(() -> state.selectedIndex() == 0, "selection follows the conversation id");
        assertEquals(BOB_ID,
                store.conversations().get(state.selectedIndex()).conversationId());
    }

    @Test
    void discoveredConversationDoesNotStealSelection() throws InterruptedException {
        ConversationStore store =
                new ConversationStore(ME, List.of(entry(ALICE_ID, ALICE_ID, "alice")));
        List<ConversationEntry> fresh =
                List.of(entry(ALICE_ID, ALICE_ID, "alice"), entry(CAROL_ID, CAROL_ID, "carol"));
        TuiSession session = session(store, () -> fresh);
        TuiState state = new TuiState();
        state.selectConversation(0);

        new TuiApp().refreshConversations(session, state, true);

        await(() -> store.conversations().size() == 2, "discovered conversation added");
        assertEquals(0, state.selectedIndex(), "selection stays on the open conversation");
        assertEquals(ALICE_ID,
                store.conversations().get(state.selectedIndex()).conversationId());
        assertEquals(TuiState.Focus.CONVERSATIONS, state.focus(), "focus untouched");
        assertEquals(TuiState.SidebarTab.CONVERSATIONS, state.sidebarTab());
    }

    @Test
    void disappearedConversationFallsBackToClampedIndex() throws InterruptedException {
        ConversationStore store = new ConversationStore(ME,
                List.of(entry(ALICE_ID, ALICE_ID, "alice"), entry(BOB_ID, BOB_ID, "bob")));
        TuiSession session = session(store,
                () -> List.of(entry(ALICE_ID, ALICE_ID, "alice")));
        TuiState state = new TuiState();
        state.selectConversation(1);

        new TuiApp().refreshConversations(session, state, true);

        await(() -> store.conversations().size() == 1, "pruned list");
        await(() -> state.selectedIndex() == 0, "selection clamped into the fresh list");
    }

    @Test
    void autoFailurePreservesVisibleListSilently() throws InterruptedException {
        ConversationStore store =
                new ConversationStore(ME, List.of(entry(ALICE_ID, ALICE_ID, "alice")));
        AtomicInteger loads = new AtomicInteger();
        TuiSession session = session(store, () -> {
            loads.incrementAndGet();
            throw failure(SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1);
        });
        TuiState state = new TuiState();
        state.setStatus("");

        new TuiApp().refreshConversations(session, state, false);

        await(() -> loads.get() == 1, "failed worker ran");
        assertEquals(1, store.conversations().size(), "visible list preserved");
        assertEquals(ALICE_ID, store.conversations().get(0).conversationId());
        assertEquals("", state.status(), "no status noise on background failure");
    }

    @Test
    void explicitFailureSurfacesStatusButKeepsList() throws InterruptedException {
        ConversationStore store =
                new ConversationStore(ME, List.of(entry(ALICE_ID, ALICE_ID, "alice")));
        TuiSession session = session(store, () -> {
            throw failure(SamvaadApiException.Kind.HTTP_ERROR, 500);
        });
        TuiState state = new TuiState();

        new TuiApp().refreshConversations(session, state, true);

        await(() -> "Could not refresh conversations.".equals(state.status()),
                "manual failure status");
        assertEquals(1, store.conversations().size(), "visible list preserved");
    }

    @Test
    void workersAreDaemonThreads() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, () -> {
            entered.countDown();
            awaitRelease(release);
            return List.of();
        });

        new TuiApp().refreshConversations(session, new TuiState(), true);

        assertTrue(entered.await(10, TimeUnit.SECONDS), "worker started");
        Thread worker = findThread("samvaad-conversations-refresh");
        assertTrue(worker != null && worker.isDaemon(),
                "background refresh must never block JVM shutdown");
        release.countDown();
        await(() -> !workerAlive("samvaad-conversations-refresh"), "worker finished");
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertTrue(release.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinWorker() throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            Thread worker = findThread("samvaad-conversations-refresh");
            if (worker == null) {
                return;
            }
            worker.join(100);
        }
        throw new AssertionError("timed out waiting for the refresh worker to finish");
    }

    private static Thread findThread(String name) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName())) {
                return thread;
            }
        }
        return null;
    }

    private static boolean workerAlive(String name) {
        Thread thread = findThread(name);
        return thread != null && thread.isAlive();
    }
}

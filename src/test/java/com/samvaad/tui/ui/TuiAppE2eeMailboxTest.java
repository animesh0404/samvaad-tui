package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.FakeHttpTransport;
import com.samvaad.tui.api.HttpResult;
import com.samvaad.tui.api.HttpTransport;
import com.samvaad.tui.bootstrap.E2eeInboxProcessor;
import com.samvaad.tui.bootstrap.E2eeMessageSender;
import com.samvaad.tui.bootstrap.E2eeRuntime;
import com.samvaad.tui.bootstrap.E2eeRuntimeFactory;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.FirstMessage;
import com.samvaad.tui.model.FriendEntry;
import com.samvaad.tui.model.FriendRequestEntry;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.model.FriendStore;
import com.samvaad.tui.model.UserLookupEntry;
import com.samvaad.tui.realtime.FakeRealtimeClient;
import com.samvaad.tui.realtime.RealtimeManager;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mailbox-poll lifecycle proofs: the render-loop tick drives inbox
 * processing on the existing 5-second cadence, overlapping polls are
 * prevented, failures never kill the tick, and polling stops without an
 * inbox (logout/unavailable E2EE). Plaintext realtime behavior is
 * untouched by construction and covered by the unchanged realtime tests.
 */
class TuiAppE2eeMailboxTest {

    private static final UUID ME = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static char[] vaultPassword() {
        return "test-only-vault-password".toCharArray();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** One real first-contact envelope from Alice to Bob, via the public sender. */
    private static String encryptedEnvelope(E2eeRuntime alice, E2eeRuntime bob, UUID bobUser,
            String text, AtomicInteger otpIds) throws Exception {
        SenderScript script = new SenderScript(bob, bobUser, otpIds);
        UUID conversationId = UUID.randomUUID();
        script.submitConversationId = conversationId;
        E2eeMessageSender sender = E2eeMessageSender.ready(new E2eeDeviceApiClient(script),
                new E2eeMessageApiClient(script), "http://localhost:8080", "token",
                alice, UUID.randomUUID());
        sender.send(UUID.randomUUID(), bobUser, "bob", text);
        JsonNode envelope = new ObjectMapper().readTree(script.capturedSubmit)
                .get("envelopes").get(0);
        return "{\"envelopeType\":\"" + envelope.get("envelopeType").asText() + "\","
                + "\"ciphertext\":\"" + envelope.get("ciphertext").asText() + "\"}";
    }

    /**
     * Path-routing scripted transport for the outbound sender: directory,
     * claim (fresh one-time prekey per call), and submit capture.
     */
    private static class SenderScript implements HttpTransport {
        final E2eeRuntime bob;
        final UUID bobUser;
        final AtomicInteger otpIds;
        final UUID bobServerDevice = UUID.randomUUID();
        UUID submitConversationId = UUID.randomUUID();
        String capturedSubmit;

        SenderScript(E2eeRuntime bob, UUID bobUser, AtomicInteger otpIds) {
            this.bob = bob;
            this.bobUser = bobUser;
            this.otpIds = otpIds;
        }

        private String bundleJson(Integer otpId, String otpB64) {
            return "{\"deviceId\":\"" + bobServerDevice + "\","
                    + "\"registrationId\":" + bob.registrationId() + ","
                    + "\"signalDeviceId\":2,"
                    + "\"deviceIdentityPublicKey\":\"" + base64(bob.identityPublicKey()) + "\","
                    + "\"signedPrekeyId\":" + bob.signedPrekeyId() + ","
                    + "\"signedPrekey\":\"" + base64(bob.signedPrekeyPublicKey()) + "\","
                    + "\"signedPrekeySignature\":\"" + base64(bob.signedPrekeySignature()) + "\","
                    + "\"hasAvailableOneTimePrekey\":" + (otpId != null) + ","
                    + (otpId != null
                            ? "\"oneTimePrekey\":{\"prekeyId\":" + otpId
                                    + ",\"publicKey\":\"" + otpB64 + "\"},"
                            : "")
                    + "\"kyberPrekeyId\":" + bob.kyberPrekeyId() + ","
                    + "\"kyberPrekey\":\"" + base64(bob.kyberPublicKey()) + "\","
                    + "\"kyberPrekeySignature\":\"" + base64(bob.kyberSignature()) + "\"}";
        }

        @Override
        public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
            return new HttpResult(200, "[" + bundleJson(null, null) + "]");
        }

        @Override
        public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
            if (path.endsWith("/one-time-prekeys/claim")) {
                E2eeRuntime.OneTimePublicKey otp =
                        bob.generateOneTimePrekeys(otpIds.getAndIncrement(), 1).get(0);
                return new HttpResult(200, bundleJson(otp.prekeyId(), base64(otp.publicKey())));
            }
            if (path.equals("/api/e2ee/messages")) {
                capturedSubmit = jsonBody;
                return new HttpResult(201, "{\"messageId\":\"" + UUID.randomUUID() + "\","
                        + "\"conversationId\":\"" + submitConversationId + "\","
                        + "\"sequenceNumber\":4,"
                        + "\"serverTimestamp\":\"2026-09-27T10:00:00\","
                        + "\"acceptedRecipientDevices\":[\"" + bobServerDevice + "\"],"
                        + "\"createdNew\":true}");
            }
            throw new IllegalStateException("unexpected POST " + path);
        }

        @Override
        public HttpResult put(String baseUrl, String path, String jsonBody, String bearerToken) {
            throw new UnsupportedOperationException();
        }
    }

    private static String mailboxJson(UUID messageId, UUID conversationId, long sequence,
            UUID senderUserId, UUID senderDeviceId, String envelopeJson) {
        // Splits the envelope object into the item shape the server returns.
        String inner = envelopeJson.substring(1, envelopeJson.length() - 1);
        return "{\"messageId\":\"" + messageId + "\","
                + "\"conversationId\":\"" + conversationId + "\","
                + "\"sequenceNumber\":" + sequence + ","
                + "\"senderUserId\":\"" + senderUserId + "\","
                + "\"senderDeviceId\":\"" + senderDeviceId + "\","
                + inner + ","
                + "\"serverTimestamp\":\"2026-09-27T10:00:00\"}";
    }

    private static TuiSession session(ConversationStore store, E2eeInboxProcessor inbox,
            FakeRealtimeClient realtimeClient) {
        MessageHistoryLoader history = (id, after, limit) -> List.of();
        RealtimeManager realtime = new RealtimeManager(
                realtimeClient, "http://localhost:8080", "token", store, history);
        return new TuiSession("alice", "http://localhost:8080", store, history, realtime,
                new FriendRequestStore(), quietFriends(), new FriendStore(), () -> List.of(),
                null, inbox, null, null);
    }

    private static FriendService quietFriends() {
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
            public FirstMessage sendFirstMessage(String username, String content, UUID requestId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static void await(BooleanSupplier check, String what) throws InterruptedException {
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
    void pollMergesDecryptedMailboxMessage(@TempDir Path aliceDir, @TempDir Path bobDir)
            throws Exception {
        E2eeRuntime alice = E2eeRuntimeFactory.initialize(aliceDir, vaultPassword());
        E2eeRuntime bob = E2eeRuntimeFactory.initialize(bobDir, vaultPassword());
        UUID aliceUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String envelope = encryptedEnvelope(alice, bob, ME, "hello bob", new AtomicInteger(500));
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(messageId, conversationId, 4L,
                aliceUser, alice.deviceId(), envelope) + "]");
        transport.addJson(200, "{\"acknowledged\":1}");
        ConversationStore store = new ConversationStore(ME, List.of());
        FakeRealtimeClient realtimeClient = new FakeRealtimeClient();
        E2eeInboxProcessor inbox = E2eeInboxProcessor.ready(
                new E2eeMessageApiClient(transport), "http://localhost:8080", "token", bob);
        TuiSession session = session(store, inbox, realtimeClient);
        TuiApp app = new TuiApp(() -> 6_000);
        TuiState state = new TuiState();

        app.pollE2eeMailbox(session, state);

        await(() -> !store.messagesOf(conversationId).isEmpty(), "mailbox merge");
        assertEquals("hello bob", store.messagesOf(conversationId).get(0).content());
        assertEquals(messageId, store.messagesOf(conversationId).get(0).messageId());
        await(() -> transport.calls().stream()
                .anyMatch(c -> c.path().equals("/api/e2ee/mailbox/ack")), "mailbox ack");
        // Plaintext path untouched: no realtime sends, no conversation-list change.
        assertTrue(realtimeClient.sends().isEmpty());
        assertTrue(store.conversations().isEmpty());
    }

    @Test
    void maybePollRespectsInterval(@TempDir Path bobDir) throws Exception {
        E2eeRuntime bob = E2eeRuntimeFactory.initialize(bobDir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        ConversationStore store = new ConversationStore(ME, List.of());
        E2eeInboxProcessor inbox = E2eeInboxProcessor.ready(
                new E2eeMessageApiClient(transport), "http://localhost:8080", "token", bob);
        TuiSession session = session(store, inbox, new FakeRealtimeClient());
        AtomicLong clock = new AtomicLong(0);
        TuiApp app = new TuiApp(clock::get);
        TuiState state = new TuiState();

        app.maybePollE2eeMailbox(session, state);
        clock.set(4_999);
        app.maybePollE2eeMailbox(session, state);
        assertTrue(transport.calls().isEmpty());

        transport.addJson(200, "[]");
        clock.set(5_000);
        app.maybePollE2eeMailbox(session, state);
        await(() -> transport.calls().size() >= 1, "mailbox poll at interval");
        assertEquals("/api/e2ee/mailbox?limit=50", transport.calls().get(0).path());
    }

    @Test
    void skipsWithoutInbox() {
        ConversationStore store = new ConversationStore(ME, List.of());
        TuiSession session = session(store, null, new FakeRealtimeClient());
        TuiApp app = new TuiApp(() -> 60_000);
        TuiState state = new TuiState();

        // Null inbox (E2EE unavailable, logged out): never touches HTTP.
        app.maybePollE2eeMailbox(session, state);
        app.pollE2eeMailbox(session, state);
    }

    @Test
    void failedPollDoesNotKillPolling(@TempDir Path bobDir) throws Exception {
        E2eeRuntime bob = E2eeRuntimeFactory.initialize(bobDir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(500, "unavailable");
        transport.addJson(200, "[]");
        ConversationStore store = new ConversationStore(ME, List.of());
        E2eeInboxProcessor inbox = E2eeInboxProcessor.ready(
                new E2eeMessageApiClient(transport), "http://localhost:8080", "token", bob);
        TuiSession session = session(store, inbox, new FakeRealtimeClient());
        TuiApp app = new TuiApp(() -> 60_000);
        TuiState state = new TuiState();

        app.pollE2eeMailbox(session, state);
        await(() -> transport.calls().size() >= 1, "first poll attempt");

        // Guard was released despite the failure: a later poll proceeds.
        // Retry until the worker has reset the guard (async by design).
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end && transport.calls().size() < 2) {
            app.pollE2eeMailbox(session, state);
            Thread.sleep(50);
        }
        assertTrue(transport.calls().size() >= 2, "second poll attempt proceeds");
    }

    @Test
    void overlappingPollsArePrevented(@TempDir Path bobDir) throws Exception {
        E2eeRuntime bob = E2eeRuntimeFactory.initialize(bobDir, vaultPassword());
        BlockingTransport transport = new BlockingTransport();
        ConversationStore store = new ConversationStore(ME, List.of());
        E2eeInboxProcessor inbox = E2eeInboxProcessor.ready(
                new E2eeMessageApiClient(transport), "http://localhost:8080", "token", bob);
        TuiSession session = session(store, inbox, new FakeRealtimeClient());
        TuiApp app = new TuiApp(() -> 60_000);
        TuiState state = new TuiState();

        app.pollE2eeMailbox(session, state);
        assertTrue(transport.entered.await(10, TimeUnit.SECONDS), "worker started");
        // A slow poll in flight: the second trigger must not start another fetch.
        app.pollE2eeMailbox(session, state);
        Thread.sleep(150);
        assertEquals(1, transport.gets.get());

        // The worker is a daemon: JVM shutdown never waits on it.
        boolean daemon = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("samvaad-e2ee-mailbox"))
                .findFirst().map(Thread::isDaemon).orElse(false);
        assertTrue(daemon, "mailbox worker is a daemon thread");
        transport.release.countDown();
    }

    /** Blocks inside GET until released; records fetch attempts. */
    private static class BlockingTransport implements HttpTransport {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger gets = new AtomicInteger();

        @Override
        public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
            gets.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "test released the poll");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new HttpResult(200, "[]");
        }

        @Override
        public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpResult put(String baseUrl, String path, String jsonBody, String bearerToken) {
            throw new UnsupportedOperationException();
        }
    }
}

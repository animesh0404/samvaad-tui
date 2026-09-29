package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.e2ee.client.CryptoTypes;
import com.samvaad.e2ee.client.SamvaadCryptoService;
import com.samvaad.e2ee.client.SamvaadCryptoServiceImpl;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.FakeHttpTransport;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Inbound proofs through the real crypto stack: real Alice/Bob runtimes,
 * real Alice-side encryption, scripted mailbox HTTP, real Bob-side
 * decryption via the library, {@link E2eePayload} decode, authoritative
 * {@link ConversationStore} merge, and acknowledgement.
 */
class E2eeInboxProcessorTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "token";

    private static char[] vaultPassword() {
        return "test-only-vault-password".toCharArray();
    }

    private record Party(E2eeRuntime runtime, UUID serverDeviceId, int signalDeviceId) {
    }

    private record WireEnvelope(String type, String ciphertextB64) {
    }

    private static Party newParty(Path dir, int signalDeviceId) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        return new Party(runtime, UUID.randomUUID(), signalDeviceId);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Encrypts {@code plaintext} from Alice to Bob through the real
     * service, capturing the outbound envelope. Mirrors the outbound
     * contract: sessions keyed by Bob's device, claim returns Bob's
     * current bundle.
     */
    private static WireEnvelope encrypt(Party alice, Party bob, UUID bobUserId,
            byte[] plaintext, AtomicInteger otpIds) {
        E2eeRuntime.OneTimePublicKey otp =
                bob.runtime().generateOneTimePrekeys(otpIds.getAndIncrement(), 1).get(0);
        CryptoTypes.RecipientBundle bundle = new CryptoTypes.RecipientBundle(
                bob.runtime().deviceId(), bobUserId, bob.signalDeviceId(),
                bob.runtime().registrationId(), bob.runtime().identityPublicKey(),
                bob.runtime().signedPrekeyId(), bob.runtime().signedPrekeyPublicKey(),
                bob.runtime().signedPrekeySignature(), otp.prekeyId(), otp.publicKey(),
                bob.runtime().kyberPrekeyId(), bob.runtime().kyberPublicKey(),
                bob.runtime().kyberSignature());
        List<CryptoTypes.OutboundEnvelope> captured = new ArrayList<>();
        SamvaadCryptoService service = new SamvaadCryptoServiceImpl(
                alice.runtime().adapter(), alice.runtime().stores(),
                (peer, claimId) -> bundle,
                (messageId, envelopes) -> captured.addAll(envelopes));
        SamvaadCryptoService.FanoutResult result = service.sendToDevices(UUID.randomUUID(),
                alice.serverDeviceId(), plaintext, List.of(bundle), Set.of());
        assertEquals(1, result.sentCount());
        assertEquals(1, captured.size());
        CryptoTypes.OutboundEnvelope envelope = captured.get(0);
        return new WireEnvelope(
                envelope.envelopeType().name(), base64(envelope.envelopeCiphertext()));
    }

    private static String mailboxJson(UUID messageId, UUID conversationId, long sequence,
            UUID senderUserId, UUID senderDeviceId, WireEnvelope envelope) {
        return "{\"messageId\":\"" + messageId + "\","
                + "\"conversationId\":\"" + conversationId + "\","
                + "\"sequenceNumber\":" + sequence + ","
                + "\"senderUserId\":\"" + senderUserId + "\","
                + "\"senderDeviceId\":\"" + senderDeviceId + "\","
                + "\"envelopeType\":\"" + envelope.type() + "\","
                + "\"ciphertext\":\"" + envelope.ciphertextB64() + "\","
                + "\"serverTimestamp\":\"2026-09-27T10:00:00\"}";
    }

    private static E2eeInboxProcessor processor(FakeHttpTransport transport, E2eeRuntime bob) {
        return E2eeInboxProcessor.ready(
                new E2eeMessageApiClient(transport), BASE_URL, TOKEN, bob);
    }

    private static long ackPostCount(FakeHttpTransport transport) {
        return transport.calls().stream()
                .filter(c -> c.path().equals("/api/e2ee/mailbox/ack"))
                .count();
    }

    @Test
    void mailboxItemDecryptsMergesAndAcks(@TempDir Path aliceDir, @TempDir Path bobDir) {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        UUID aliceUser = UUID.randomUUID();
        UUID bobUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        WireEnvelope envelope = encrypt(alice, bob, bobUser,
                E2eePayload.encodeDirectText("hello bob"), new AtomicInteger(500));
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(messageId, conversationId, 4L,
                aliceUser, alice.runtime().deviceId(), envelope) + "]");
        transport.addJson(200, "{\"acknowledged\":1}");
        ConversationStore store = new ConversationStore(bobUser, List.of());

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        assertTrue(result.ackComplete());
        assertTrue(result.failures().isEmpty());
        assertTrue(result.pendingAck().isEmpty());
        assertEquals(1, result.consumed().size());
        assertEquals(messageId, result.consumed().get(0).messageId());
        assertEquals("hello bob", result.consumed().get(0).content());
        List<MessageEntry> merged = store.messagesOf(conversationId);
        assertEquals(1, merged.size());
        assertEquals(messageId, merged.get(0).messageId());
        assertEquals(aliceUser, merged.get(0).senderUserId());
        assertEquals(4L, merged.get(0).sequenceNumber());
        assertEquals("hello bob", merged.get(0).content());
        assertEquals(LocalDateTime.of(2026, 9, 27, 10, 0, 0), merged.get(0).serverTimestamp());
        assertEquals(1, ackPostCount(transport));
    }

    @Test
    void multipleItemsProcessInOrderWithSingleAck(@TempDir Path aliceDir, @TempDir Path bobDir,
            @TempDir Path carolDir) {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        Party carol = newParty(carolDir, 3);
        UUID aliceUser = UUID.randomUUID();
        UUID bobUser = UUID.randomUUID();
        UUID carolUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        // Two independent first-contact senders: each envelope establishes
        // with its own one-time prekey, so both decrypt deterministically.
        WireEnvelope first = encrypt(alice, bob, bobUser,
                E2eePayload.encodeDirectText("one"), new AtomicInteger(500));
        WireEnvelope second = encrypt(carol, bob, bobUser,
                E2eePayload.encodeDirectText("two"), new AtomicInteger(700));
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(firstId, conversationId, 4L,
                aliceUser, alice.runtime().deviceId(), first) + ","
                + mailboxJson(secondId, conversationId, 5L,
                        carolUser, carol.runtime().deviceId(), second) + "]");
        transport.addJson(200, "{\"acknowledged\":2}");
        ConversationStore store = new ConversationStore(bobUser, List.of());

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        assertTrue(result.ackComplete());
        assertEquals(2, result.consumed().size());
        assertEquals("one", result.consumed().get(0).content());
        assertEquals("two", result.consumed().get(1).content());
        assertEquals(List.of("one", "two"), store.messagesOf(conversationId).stream()
                .map(MessageEntry::content).toList());
        assertEquals(1, ackPostCount(transport));
    }

    @Test
    void repeatedPrekeyEnvelopeFailsClosedWithoutAck(
            @TempDir Path aliceDir, @TempDir Path bobDir) {
        // Library behavior: a reused session repeats the original PREKEY_INIT
        // bytes (same one-time prekey reference) until the peer's first
        // reply, so a second rapid send from the same sender references the
        // already-consumed prekey and is deterministically rejected. The
        // processor must fail that envelope closed without acking it while
        // still consuming and acking the first message.
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        UUID aliceUser = UUID.randomUUID();
        UUID bobUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AtomicInteger otpIds = new AtomicInteger(500);
        WireEnvelope first = encrypt(alice, bob, bobUser,
                E2eePayload.encodeDirectText("one"), otpIds);
        WireEnvelope second = encrypt(alice, bob, bobUser,
                E2eePayload.encodeDirectText("two"), otpIds);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(firstId, conversationId, 4L,
                aliceUser, alice.runtime().deviceId(), first) + ","
                + mailboxJson(secondId, conversationId, 5L,
                        aliceUser, alice.runtime().deviceId(), second) + "]");
        transport.addJson(200, "{\"acknowledged\":1}");
        ConversationStore store = new ConversationStore(bobUser, List.of());

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        assertEquals(1, result.consumed().size());
        assertEquals("one", result.consumed().get(0).content());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().containsKey(secondId));
        assertTrue(result.ackComplete());
        assertEquals(1, ackPostCount(transport));
        assertEquals(List.of("one"), store.messagesOf(conversationId).stream()
                .map(MessageEntry::content).toList());
    }

    @Test
    void partialAckCountKeepsMessagesPending(@TempDir Path aliceDir, @TempDir Path bobDir) {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        UUID aliceUser = UUID.randomUUID();
        UUID bobUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        WireEnvelope envelope = encrypt(alice, bob, bobUser,
                E2eePayload.encodeDirectText("hello bob"), new AtomicInteger(500));
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(messageId, conversationId, 4L,
                aliceUser, alice.runtime().deviceId(), envelope) + "]");
        transport.addJson(200, "{\"acknowledged\":0}");
        ConversationStore store = new ConversationStore(bobUser, List.of());

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        assertTrue(result.consumed().isEmpty());
        assertEquals(List.of(messageId), result.pendingAck());
        assertFalse(result.ackComplete());
        assertTrue(result.ackErrorOrNull() != null && !result.ackErrorOrNull().isBlank());
        assertEquals(1, store.messagesOf(conversationId).size());
    }

    @Test
    void decryptFailureDoesNotAck(@TempDir Path aliceDir, @TempDir Path bobDir) {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        UUID bobUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String garbage = base64("not-a-signal-message".getBytes());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(messageId, conversationId, 4L,
                UUID.randomUUID(), alice.runtime().deviceId(),
                new WireEnvelope("PREKEY_INIT", garbage)) + "]");
        ConversationStore store = new ConversationStore(bobUser, List.of());

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        assertTrue(result.consumed().isEmpty());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().containsKey(messageId));
        assertTrue(store.messagesOf(conversationId).isEmpty());
        assertEquals(0, ackPostCount(transport));
    }

    @Test
    void malformedPayloadDoesNotAck(@TempDir Path aliceDir, @TempDir Path bobDir) {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        UUID aliceUser = UUID.randomUUID();
        UUID bobUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        // Decrypts fine (opaque to crypto) but is truncated payload framing.
        WireEnvelope envelope = encrypt(alice, bob, bobUser,
                new byte[] {1}, new AtomicInteger(500));
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(messageId, conversationId, 4L,
                aliceUser, alice.runtime().deviceId(), envelope) + "]");
        ConversationStore store = new ConversationStore(bobUser, List.of());

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        assertTrue(result.consumed().isEmpty());
        assertEquals(1, result.failures().size());
        assertTrue(store.messagesOf(conversationId).isEmpty());
        assertEquals(0, ackPostCount(transport));
    }

    @Test
    void unsupportedPayloadTypeDoesNotAck(@TempDir Path aliceDir, @TempDir Path bobDir) {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        UUID aliceUser = UUID.randomUUID();
        UUID bobUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        // Decrypts fine but carries an unknown application type.
        WireEnvelope envelope = encrypt(alice, bob, bobUser,
                new byte[] {1, 7, 'x'}, new AtomicInteger(500));
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(messageId, conversationId, 4L,
                aliceUser, alice.runtime().deviceId(), envelope) + "]");
        ConversationStore store = new ConversationStore(bobUser, List.of());

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        assertTrue(result.consumed().isEmpty());
        assertEquals(1, result.failures().size());
        assertTrue(store.messagesOf(conversationId).isEmpty());
        assertEquals(0, ackPostCount(transport));
    }

    @Test
    void ackFailureKeepsMessagePending(@TempDir Path aliceDir, @TempDir Path bobDir) {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        UUID aliceUser = UUID.randomUUID();
        UUID bobUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        WireEnvelope envelope = encrypt(alice, bob, bobUser,
                E2eePayload.encodeDirectText("hello bob"), new AtomicInteger(500));
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(messageId, conversationId, 4L,
                aliceUser, alice.runtime().deviceId(), envelope) + "]");
        transport.addJson(500, "unavailable");
        ConversationStore store = new ConversationStore(bobUser, List.of());

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        assertTrue(result.consumed().isEmpty());
        assertEquals(List.of(messageId), result.pendingAck());
        assertFalse(result.ackComplete());
        assertTrue(result.ackErrorOrNull() != null && !result.ackErrorOrNull().isBlank());
        // Merged locally but NOT reported as consumed: retry must re-ack.
        assertEquals(1, store.messagesOf(conversationId).size());
    }

    @Test
    void duplicateMessageFollowsStoreSemantics(@TempDir Path aliceDir, @TempDir Path bobDir) {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 2);
        UUID aliceUser = UUID.randomUUID();
        UUID bobUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        WireEnvelope envelope = encrypt(alice, bob, bobUser,
                E2eePayload.encodeDirectText("fresh text"), new AtomicInteger(500));
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "[" + mailboxJson(messageId, conversationId, 4L,
                aliceUser, alice.runtime().deviceId(), envelope) + "]");
        transport.addJson(200, "{\"acknowledged\":1}");
        ConversationStore store = new ConversationStore(bobUser, List.of());
        store.mergeMessages(conversationId, List.of(new MessageEntry(messageId, aliceUser, 4L,
                "original text", LocalDateTime.of(2026, 9, 27, 10, 0, 0), null)));

        E2eeInboxProcessor.InboxResult result =
                processor(transport, bob.runtime()).process(store);

        // Existing authoritative merge keeps the first entry per message id.
        List<MessageEntry> merged = store.messagesOf(conversationId);
        assertEquals(1, merged.size());
        assertEquals("original text", merged.get(0).content());
        // Processing itself succeeded, so the envelope is still acked.
        assertTrue(result.ackComplete());
        assertEquals(1, ackPostCount(transport));
    }
}

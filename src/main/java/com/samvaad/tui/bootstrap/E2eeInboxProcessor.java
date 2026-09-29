package com.samvaad.tui.bootstrap;

import com.samvaad.e2ee.client.ClientCryptoStore;
import com.samvaad.e2ee.client.CryptoTypes;
import com.samvaad.e2ee.client.SamvaadCryptoService;
import com.samvaad.e2ee.client.SamvaadCryptoServiceImpl;
import com.samvaad.e2ee.client.signal.LibSignalAdapter;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.api.dto.AckMailboxResponse;
import com.samvaad.tui.api.dto.E2eeCiphertextItem;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Inbound encrypted direct messaging: mailbox fetch, per-envelope
 * decryption, application-payload decode, authoritative store merge, and
 * acknowledgement.
 *
 * <pre>
 * GET /api/e2ee/mailbox → per item: decrypt → E2eePayload decode →
 * ConversationStore.mergeMessages → POST /api/e2ee/mailbox/ack
 * </pre>
 *
 * <p>All crypto stays inside {@code e2ee-client}: this class only selects
 * the decrypt path from the server-supplied envelope type verbatim
 * (never by trial-parsing) and forwards the resulting bytes to
 * {@link E2eePayload}. No private key material is accessed; sessions,
 * trust, and key custody stay in the runtime's adapter/store pair.
 *
 * <p>A mailbox item is acknowledged only after decryption, payload
 * decoding, and store merge all succeed. Anything else leaves the
 * envelope unacknowledged for retry and records a structured per-message
 * failure. There is no plaintext fallback, ever: failures never touch
 * the plaintext realtime/history paths.
 *
 * <p>Items are processed deterministically in server (oldest-first)
 * order. The sync cursor is never touched: fetch/ack independence is a
 * server invariant this slice preserves.
 */
public final class E2eeInboxProcessor {

    /** Default mailbox page size, matching the server default. */
    static final int DEFAULT_LIMIT = 50;

    private final E2eeMessageApiClient messages;
    private final String serverUrl;
    private final String accessToken;
    private final SamvaadCryptoService service;

    private E2eeInboxProcessor(E2eeMessageApiClient messages, String serverUrl,
            String accessToken, SamvaadCryptoService service) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * Ready processor over an enrolled runtime. The claim/submit seams are
     * inert: the decrypt path never invokes them.
     */
    public static E2eeInboxProcessor ready(E2eeMessageApiClient messages, String serverUrl,
            String accessToken, E2eeRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        LibSignalAdapter adapter = runtime.adapter();
        ClientCryptoStore stores = runtime.stores();
        SamvaadCryptoService service = new SamvaadCryptoServiceImpl(adapter, stores,
                (peer, claimId) -> {
                    throw new E2eeException("No claims on the inbound path.");
                },
                (messageId, envelopes) -> {
                    throw new E2eeException("No submits on the inbound path.");
                });
        return new E2eeInboxProcessor(messages, serverUrl, accessToken, service);
    }

    /**
     * One consumed message: merged into the store and acknowledged.
     * {@code senderDeviceId} has no slot in {@link MessageEntry} and is
     * intentionally not carried; every other server field is preserved.
     */
    public record ConsumedMessage(UUID messageId, UUID conversationId, long sequenceNumber,
            UUID senderUserId, String content) {
        public ConsumedMessage {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(senderUserId, "senderUserId");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * Outcome of one mailbox pass. {@code consumed} holds only messages
     * that were merged <em>and</em> acknowledged; anything merged but left
     * unacknowledged (ack failure) or never merged (per-item failure)
     * appears in {@code pendingAck} / {@code failures} instead and must
     * be retried — never reported as fully consumed.
     */
    public record InboxResult(List<ConsumedMessage> consumed, List<UUID> pendingAck,
            Map<UUID, String> failures, boolean ackComplete, String ackErrorOrNull) {
        public InboxResult {
            consumed = List.copyOf(consumed);
            pendingAck = List.copyOf(pendingAck);
            failures = Map.copyOf(failures);
        }
    }

    /**
     * Runs one deterministic mailbox pass against {@code store}.
     *
     * @throws SamvaadApiException when the mailbox fetch itself fails
     *         (authentication, transport, HTTP); per-item and ack problems
     *         are reported inside {@link InboxResult} instead
     */
    public InboxResult process(ConversationStore store) {
        Objects.requireNonNull(store, "store");
        List<E2eeCiphertextItem> items = messages.fetchMailbox(serverUrl, accessToken, DEFAULT_LIMIT);
        List<ConsumedMessage> merged = new ArrayList<>(items.size());
        List<UUID> mergedIds = new ArrayList<>(items.size());
        Map<UUID, String> failures = new LinkedHashMap<>();
        for (E2eeCiphertextItem item : items) {
            try {
                merged.add(decryptAndMerge(store, item));
                mergedIds.add(item.messageId());
            } catch (RuntimeException e) {
                failures.put(item.messageId(), safeMessage(e));
            }
        }
        if (mergedIds.isEmpty()) {
            return new InboxResult(List.of(), List.of(), failures, true, null);
        }
        AckMailboxResponse acked;
        try {
            acked = messages.acknowledgeMailbox(serverUrl, accessToken, List.copyOf(mergedIds));
        } catch (RuntimeException e) {
            return new InboxResult(List.of(), mergedIds, failures, false, safeMessage(e));
        }
        if (acked == null || acked.acknowledged() != mergedIds.size()) {
            int count = acked == null ? 0 : acked.acknowledged();
            return new InboxResult(List.of(), mergedIds, failures, false,
                    "Mailbox acknowledgement incomplete (" + count + " of " + mergedIds.size()
                            + " acknowledged).");
        }
        return new InboxResult(merged, List.of(), failures, true, null);
    }

    private ConsumedMessage decryptAndMerge(ConversationStore store, E2eeCiphertextItem item) {
        CryptoTypes.EnvelopeType kind;
        try {
            kind = CryptoTypes.EnvelopeType.valueOf(item.envelopeType());
        } catch (IllegalArgumentException e) {
            throw new E2eeException("Unsupported E2EE envelope type.", e);
        }
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(item.ciphertext().trim());
        } catch (IllegalArgumentException e) {
            throw new E2eeException("E2EE envelope ciphertext is not valid Base64.", e);
        }
        // Sessions are keyed by peer device: for inbound envelopes the peer
        // is the sender, so both decrypt id parameters take the sender id.
        byte[] plaintext = service.decrypt(item.senderDeviceId(), item.senderDeviceId(),
                kind, ciphertext);
        E2eePayload.DecodedPayload payload = E2eePayload.decode(plaintext);
        MessageEntry entry = new MessageEntry(item.messageId(), item.senderUserId(),
                item.sequenceNumber(), payload.content(), item.serverTimestamp(), null);
        store.mergeMessages(item.conversationId(), List.of(entry));
        if (item.sequenceNumber() > 0) {
            store.updateHighWater(item.conversationId(), item.sequenceNumber());
        }
        return new ConsumedMessage(item.messageId(), item.conversationId(),
                item.sequenceNumber(), item.senderUserId(), payload.content());
    }

    private static String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}

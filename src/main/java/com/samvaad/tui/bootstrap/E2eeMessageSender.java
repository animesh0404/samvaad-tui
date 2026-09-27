package com.samvaad.tui.bootstrap;

import com.samvaad.e2ee.client.ClientCryptoStore;
import com.samvaad.e2ee.client.CryptoException;
import com.samvaad.e2ee.client.CryptoTypes;
import com.samvaad.e2ee.client.SamvaadCryptoService;
import com.samvaad.e2ee.client.SamvaadCryptoServiceImpl;
import com.samvaad.e2ee.client.signal.LibSignalAdapter;
import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.api.dto.ClaimPrekeyResponse;
import com.samvaad.tui.api.dto.E2eeEnvelopeRequest;
import com.samvaad.tui.api.dto.RecipientDeviceResponse;
import com.samvaad.tui.api.dto.SubmitE2eeMessageRequest;
import com.samvaad.tui.api.dto.SubmitE2eeMessageResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outbound encrypted direct messaging. One logical message fans out to
 * every active recipient device from the server directory:
 *
 * <pre>
 * directory → listed bundles → SamvaadCryptoService
 *   → per-device claim (service-owned request ids) → establish/encrypt
 *   → per-device ciphertext envelopes → POST /api/e2ee/messages
 * </pre>
 *
 * <p>All crypto orchestration stays inside {@code e2ee-client}: session
 * establishment, signed-prekey verification, PQXDH/Kyber, envelope-type
 * selection, and the crash-safe outbound state machine (including
 * byte-identical replay after a failed submit). This class only provides
 * the two transport seams and maps between library and HTTP types.
 *
 * <p>No plaintext fallback, ever: an E2EE failure throws
 * {@link E2eeSendException} and never touches the plaintext STOMP path.
 * The UI operates on conversation/message text only and never sees
 * devices, sessions, claims, or ciphertext.
 */
public final class E2eeMessageSender {

    private final E2eeDeviceApiClient devices;
    private final E2eeMessageApiClient messages;
    private final String serverUrl;
    private final String accessToken;
    private final LibSignalAdapter adapter;
    private final ClientCryptoStore stores;
    private final UUID senderDeviceId;
    private final String disabledReasonOrNull;

    private E2eeMessageSender(E2eeDeviceApiClient devices, E2eeMessageApiClient messages,
            String serverUrl, String accessToken, LibSignalAdapter adapter,
            ClientCryptoStore stores, UUID senderDeviceId, String disabledReasonOrNull) {
        this.devices = devices;
        this.messages = messages;
        this.serverUrl = serverUrl;
        this.accessToken = accessToken;
        this.adapter = adapter;
        this.stores = stores;
        this.senderDeviceId = senderDeviceId;
        this.disabledReasonOrNull = disabledReasonOrNull;
    }

    /**
     * Ready sender. {@code senderDeviceId} must be the server device bound
     * to the current session (only a device enrolled by this launch
     * qualifies); the server rejects anything else.
     */
    public static E2eeMessageSender ready(E2eeDeviceApiClient devices, E2eeMessageApiClient messages,
            String serverUrl, String accessToken, E2eeRuntime runtime, UUID senderDeviceId) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        return new E2eeMessageSender(Objects.requireNonNull(devices, "devices"),
                Objects.requireNonNull(messages, "messages"),
                Objects.requireNonNull(serverUrl, "serverUrl"),
                Objects.requireNonNull(accessToken, "accessToken"),
                runtime.adapter(), runtime.stores(), senderDeviceId, null);
    }

    /**
     * Disabled sender that fails fast with {@code reason} on any send,
     * without touching the network. Used when E2EE was set up but cannot
     * submit with this session (pending approval, adopted device).
     */
    public static E2eeMessageSender disabled(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new E2eeMessageSender(null, null, null, null, null, null, null, reason);
    }

    /**
     * Outcome of one logical encrypted send.
     */
    public record SentMessage(UUID messageRequestId, UUID conversationId, long sequenceNumber,
            List<UUID> acceptedRecipientDevices, List<String> envelopeTypes) {
        public SentMessage {
            Objects.requireNonNull(messageRequestId, "messageRequestId");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(acceptedRecipientDevices, "acceptedRecipientDevices");
            Objects.requireNonNull(envelopeTypes, "envelopeTypes");
            acceptedRecipientDevices = List.copyOf(acceptedRecipientDevices);
            envelopeTypes = List.copyOf(envelopeTypes);
        }
    }

    /**
     * Sends {@code text} to every active device of the recipient user.
     *
     * @param messageRequestId client idempotency key; retries of the same
     *                         logical message must reuse it so the service
     *                         replays byte-identical envelopes
     * @param recipientUserId recipient's Samvaad user id (Signal address name)
     * @param recipientUsername recipient's username for directory lookup
     * @param text message text, framed by {@link E2eePayload} before encryption
     * @throws E2eeSendException when any recipient device cannot be sent to;
     *                           carries per-device outcomes, never falls back
     */
    public SentMessage send(UUID messageRequestId, UUID recipientUserId, String recipientUsername,
            String text) {
        if (disabledReasonOrNull != null) {
            throw new E2eeSendException(disabledReasonOrNull, Map.of());
        }
        Objects.requireNonNull(messageRequestId, "messageRequestId");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        if (recipientUsername == null || recipientUsername.isBlank()) {
            throw new E2eeSendException("Cannot send encrypted: recipient username is unknown.", Map.of());
        }
        Objects.requireNonNull(text, "text");

        byte[] payload = E2eePayload.encodeDirectText(text);
        List<RecipientDeviceResponse> directory =
                messages.listRecipientDevices(serverUrl, accessToken, recipientUsername);
        if (directory.isEmpty()) {
            throw new E2eeSendException(
                    "Cannot send encrypted: recipient has no active devices.", Map.of());
        }
        Map<UUID, RecipientContext> contexts = new LinkedHashMap<>();
        List<CryptoTypes.RecipientBundle> bundles = new ArrayList<>(directory.size());
        for (RecipientDeviceResponse entry : directory) {
            RecipientContext context = RecipientContext.of(recipientUserId, entry);
            contexts.put(entry.deviceId(), context);
            bundles.add(context.listedBundle());
        }

        AtomicReference<SubmitE2eeMessageResponse> submitted = new AtomicReference<>();
        AtomicReference<List<String>> submittedTypes = new AtomicReference<>(List.of());
        SamvaadCryptoService service = new SamvaadCryptoServiceImpl(adapter, stores,
                new HttpClaimClient(contexts),
                new HttpSubmitClient(messageRequestId, submitted, submittedTypes));
        SamvaadCryptoService.FanoutResult result = service.sendToDevices(messageRequestId,
                senderDeviceId, payload, List.copyOf(bundles), Set.of());
        Map<UUID, String> outcomes = new LinkedHashMap<>();
        for (Map.Entry<UUID, SamvaadCryptoService.DeviceOutcome> entry : result.outcomes().entrySet()) {
            outcomes.put(entry.getKey(), entry.getValue().name());
        }
        boolean allSent = result.outcomes().values().stream()
                .allMatch(o -> o == SamvaadCryptoService.DeviceOutcome.SENT);
        if (!allSent) {
            throw new E2eeSendException(
                    "Encrypted send incomplete: " + describe(outcomes) + ".", Map.copyOf(outcomes));
        }
        SubmitE2eeMessageResponse response = submitted.get();
        if (response == null) {
            // Every slot was already ACKED by an earlier attempt with the
            // same request id, so the service submitted nothing new. The
            // message was already delivered; report that instead of
            // inventing server data. Fresh logical messages always use a
            // fresh request id.
            throw new E2eeSendException(
                    "Encrypted message was already sent (duplicate request id).",
                    Map.copyOf(outcomes));
        }
        return new SentMessage(messageRequestId, response.conversationId(), response.sequenceNumber(),
                response.acceptedRecipientDevices() == null
                        ? List.of()
                        : response.acceptedRecipientDevices(),
                submittedTypes.get());
    }

    private static String describe(Map<UUID, String> outcomes) {
        long sent = outcomes.values().stream().filter("SENT"::equals).count();
        return sent + " of " + outcomes.size() + " devices sent " + outcomes;
    }

    /**
     * Per-device recipient context: the directory entry plus the owning
     * user id (known from the conversation/friend record, never from the
     * directory payload) for Signal address construction.
     */
    private record RecipientContext(UUID userId, RecipientDeviceResponse directory) {

        static RecipientContext of(UUID userId, RecipientDeviceResponse directory) {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(directory, "directory");
            return new RecipientContext(userId, directory);
        }

        /**
         * Pre-claim bundle from the directory listing (no OTPK body yet).
         * The service claims per device only when no usable session exists.
         */
        CryptoTypes.RecipientBundle listedBundle() {
            return new CryptoTypes.RecipientBundle(directory.deviceId(), userId,
                    directory.signalDeviceId(), directory.registrationId(),
                    base64(directory.deviceIdentityPublicKey()), directory.signedPrekeyId(),
                    base64(directory.signedPrekey()), base64(directory.signedPrekeySignature()),
                    null, null, directory.kyberPrekeyId(),
                    directory.kyberPrekey() == null ? null : base64(directory.kyberPrekey()),
                    directory.kyberPrekeySignature() == null
                            ? null
                            : base64(directory.kyberPrekeySignature()));
        }

        /**
         * Full post-claim bundle: the claim response plus the user id from
         * this context. The claim never carries the user id; it is known
         * from the directory lookup that produced this context.
         */
        CryptoTypes.RecipientBundle claimedBundle(ClaimPrekeyResponse claimed) {
            Integer otpId = claimed.oneTimePrekey() == null
                    ? null
                    : claimed.oneTimePrekey().prekeyId();
            byte[] otp = claimed.oneTimePrekey() == null
                    ? null
                    : base64(claimed.oneTimePrekey().publicKey());
            return new CryptoTypes.RecipientBundle(claimed.deviceId(), userId,
                    claimed.signalDeviceId(), claimed.registrationId(),
                    base64(claimed.deviceIdentityPublicKey()), claimed.signedPrekeyId(),
                    base64(claimed.signedPrekey()), base64(claimed.signedPrekeySignature()),
                    otpId, otp, claimed.kyberPrekeyId(),
                    claimed.kyberPrekey() == null ? null : base64(claimed.kyberPrekey()),
                    claimed.kyberPrekeySignature() == null
                            ? null
                            : base64(claimed.kyberPrekeySignature()));
        }
    }

    private static byte[] base64(String value) {
        if (value == null || value.isBlank()) {
            throw new E2eeException("E2EE directory payload is missing key material.");
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new E2eeException("E2EE directory payload is not valid Base64.", e);
        }
    }

    private final class HttpClaimClient implements SamvaadCryptoService.ClaimClient {

        private final Map<UUID, RecipientContext> contexts;

        HttpClaimClient(Map<UUID, RecipientContext> contexts) {
            this.contexts = contexts;
        }

        @Override
        public CryptoTypes.RecipientBundle claim(UUID recipientDeviceId, UUID claimRequestId) {
            RecipientContext context = contexts.get(recipientDeviceId);
            if (context == null) {
                throw new CryptoException.ClaimFailedException(
                        "Claim for unknown recipient device.");
            }
            ClaimPrekeyResponse claimed;
            try {
                claimed = messages.claimPrekey(serverUrl, accessToken, recipientDeviceId, claimRequestId);
            } catch (SamvaadApiException e) {
                throw switch (e.kind()) {
                    case NOT_FOUND -> new CryptoException.ClaimFailedException(
                            "Recipient device is gone: " + e.getMessage());
                    case CONFLICT -> new CryptoException.TransientException(
                            "Prekey claim conflict, retry replays the same request: " + e.getMessage());
                    case SERVER_UNAVAILABLE, HTTP_ERROR ->
                            new CryptoException.TransientException("Prekey claim failed: " + e.getMessage());
                    default -> new E2eeException("Prekey claim failed: " + e.getMessage(), e);
                };
            }
            if (!claimed.deviceId().equals(recipientDeviceId)) {
                throw new CryptoException.ClaimFailedException("Claim returned a different device.");
            }
            return context.claimedBundle(claimed);
        }
    }

    private final class HttpSubmitClient implements SamvaadCryptoService.SubmitClient {

        private final UUID messageRequestId;
        private final AtomicReference<SubmitE2eeMessageResponse> submitted;
        private final AtomicReference<List<String>> submittedTypes;

        HttpSubmitClient(UUID messageRequestId, AtomicReference<SubmitE2eeMessageResponse> submitted,
                AtomicReference<List<String>> submittedTypes) {
            this.messageRequestId = messageRequestId;
            this.submitted = submitted;
            this.submittedTypes = submittedTypes;
        }

        @Override
        public void submit(UUID submittedRequestId, List<CryptoTypes.OutboundEnvelope> envelopes) {
            List<E2eeEnvelopeRequest> wire = new ArrayList<>(envelopes.size());
            for (CryptoTypes.OutboundEnvelope envelope : envelopes) {
                wire.add(toEnvelopeRequest(envelope));
            }
            SubmitE2eeMessageResponse response;
            try {
                response = messages.submitMessage(serverUrl, accessToken,
                        new SubmitE2eeMessageRequest(messageRequestId, List.copyOf(wire)));
            } catch (SamvaadApiException e) {
                throw switch (e.kind()) {
                    case SERVER_UNAVAILABLE, HTTP_ERROR ->
                            new CryptoException.TransientException("Message submit failed: " + e.getMessage());
                    default -> new E2eeException("Message submit failed: " + e.getMessage(), e);
                };
            }
            submitted.set(response);
            submittedTypes.set(wire.stream().map(E2eeEnvelopeRequest::envelopeType).toList());
        }
    }

    /**
     * Maps one library envelope to its wire form using the authoritative
     * library envelope type verbatim. Package-visible for mapping tests.
     */
    static E2eeEnvelopeRequest toEnvelopeRequest(CryptoTypes.OutboundEnvelope envelope) {
        return new E2eeEnvelopeRequest(envelope.senderDeviceId(), envelope.recipientDeviceId(),
                envelope.envelopeType().name(),
                Base64.getEncoder().encodeToString(envelope.envelopeCiphertext()));
    }
}

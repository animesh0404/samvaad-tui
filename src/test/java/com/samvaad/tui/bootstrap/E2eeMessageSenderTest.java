package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.e2ee.client.CryptoTypes;
import com.samvaad.e2ee.client.SamvaadCryptoService;
import com.samvaad.e2ee.client.SamvaadCryptoServiceImpl;
import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.HttpResult;
import com.samvaad.tui.api.HttpTransport;
import com.samvaad.tui.api.SamvaadApiException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Encrypted-send proofs through the real crypto stack: scripted HTTP
 * directory/claim/submit against real Alice/Bob runtimes, real
 * {@link SamvaadCryptoService} fan-out, and real Bob-side decryption of
 * the submitted ciphertext via the library.
 */
class E2eeMessageSenderTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "token";

    private static char[] vaultPassword() {
        return "test-only-vault-password".toCharArray();
    }

    /** Scripted E2EE HTTP: fixed directory, per-device claim suppliers, scripted submit. */
    private static class ScriptedTransport implements HttpTransport {
        record Call(String method, String path, String body) {
        }

        final List<Call> calls = new ArrayList<>();
        String directoryBody = "[]";
        final Map<UUID, Supplier<String>> claims = new LinkedHashMap<>();
        Function<String, HttpResult> submitHandler =
                body -> {
                    throw new IllegalStateException("unexpected submit");
                };

        @Override
        public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
            calls.add(new Call("GET", pathAndQuery, null));
            return new HttpResult(200, directoryBody);
        }

        @Override
        public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
            calls.add(new Call("POST", path, jsonBody));
            if (path.endsWith("/one-time-prekeys/claim")) {
                UUID device = UUID.fromString(path.split("/")[4]);
                Supplier<String> claim = claims.get(device);
                if (claim == null) {
                    throw new IllegalStateException("no claim scripted for " + device);
                }
                return new HttpResult(200, claim.get());
            }
            if (path.equals("/api/e2ee/messages")) {
                return submitHandler.apply(jsonBody);
            }
            throw new IllegalStateException("unexpected POST " + path);
        }

        @Override
        public HttpResult put(String baseUrl, String path, String jsonBody, String bearerToken) {
            throw new UnsupportedOperationException();
        }

        List<Call> postsTo(String path) {
            return calls.stream().filter(c -> c.method().equals("POST") && c.path().equals(path)).toList();
        }
    }

    private record Party(E2eeRuntime runtime, UUID serverDeviceId, int signalDeviceId) {
    }

    private static Party newParty(Path dir, int signalDeviceId) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        return new Party(runtime, UUID.randomUUID(), signalDeviceId);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String bundleJson(Party party, UUID userId, boolean withOtpk, Integer otpId,
            String otpB64) {
        String identity = base64(party.runtime().identityPublicKey());
        return "{\"deviceId\":\"" + party.serverDeviceId() + "\","
                + "\"registrationId\":" + party.runtime().registrationId() + ","
                + "\"signalDeviceId\":" + party.signalDeviceId() + ","
                + "\"deviceIdentityPublicKey\":\"" + identity + "\","
                + "\"signedPrekeyId\":" + party.runtime().signedPrekeyId() + ","
                + "\"signedPrekey\":\"" + base64(party.runtime().signedPrekeyPublicKey()) + "\","
                + "\"signedPrekeySignature\":\"" + base64(party.runtime().signedPrekeySignature()) + "\","
                + (withOtpk
                        ? "\"hasAvailableOneTimePrekey\":true,"
                        : "\"hasAvailableOneTimePrekey\":false,")
                + (otpId != null
                        ? "\"oneTimePrekey\":{\"prekeyId\":" + otpId + ",\"publicKey\":\"" + otpB64 + "\"},"
                        : "")
                + "\"kyberPrekeyId\":" + party.runtime().kyberPrekeyId() + ","
                + "\"kyberPrekey\":\"" + base64(party.runtime().kyberPublicKey()) + "\","
                + "\"kyberPrekeySignature\":\"" + base64(party.runtime().kyberSignature()) + "\""
                + "}";
    }

    private static E2eeMessageSender sender(ScriptedTransport transport, E2eeRuntime alice,
            UUID aliceServerDevice) {
        return E2eeMessageSender.ready(new E2eeDeviceApiClient(transport),
                new E2eeMessageApiClient(transport), BASE_URL, TOKEN, alice, aliceServerDevice);
    }

    private static SamvaadCryptoService bobService(E2eeRuntime bob) {
        return new SamvaadCryptoServiceImpl(bob.adapter(), bob.stores(),
                (peer, claimId) -> {
                    throw new AssertionError("no claims on the decrypt path");
                },
                (messageId, envelopes) -> {
                });
    }

    private static String submitJson(UUID conversationId, List<UUID> accepted, boolean createdNew) {
        String ids = accepted.stream().map(id -> "\"" + id + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        return "{\"messageId\":\"" + UUID.randomUUID() + "\","
                + "\"conversationId\":\"" + conversationId + "\","
                + "\"sequenceNumber\":4,\"serverTimestamp\":\"2026-09-27T10:00:00\","
                + "\"acceptedRecipientDevices\":[" + ids + "],"
                + "\"createdNew\":" + createdNew + "}";
    }

    @Test
    void singleDeviceRealEncryptionRoundTrip(@TempDir Path aliceDir, @TempDir Path bobDir)
            throws Exception {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 1);
        UUID bobUser = UUID.randomUUID();
        UUID aliceServerDevice = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AtomicInteger bobOtpIds = new AtomicInteger(500);
        try {
            ScriptedTransport transport = new ScriptedTransport();
            transport.directoryBody = "[" + bundleJson(bob, bobUser, true, null, null) + "]";
            transport.claims.put(bob.serverDeviceId(), () -> {
                int otpId = bobOtpIds.getAndIncrement();
                E2eeRuntime.OneTimePublicKey otp =
                        bob.runtime().generateOneTimePrekeys(otpId, 1).get(0);
                return bundleJson(bob, bobUser, true, otpId, base64(otp.publicKey()));
            });
            transport.submitHandler = body -> new HttpResult(201,
                    submitJson(conversationId, List.of(bob.serverDeviceId()), true));

            UUID messageId = UUID.randomUUID();
            E2eeMessageSender.SentMessage sent =
                    sender(transport, alice.runtime(), aliceServerDevice)
                            .send(messageId, bobUser, "bob", "hello alice");

            assertEquals(messageId, sent.messageRequestId());
            assertEquals(conversationId, sent.conversationId());
            assertEquals(4L, sent.sequenceNumber());
            assertEquals(List.of(bob.serverDeviceId()), sent.acceptedRecipientDevices());
            assertEquals(List.of("PREKEY_INIT"), sent.envelopeTypes());

            List<ScriptedTransport.Call> submits =
                    transport.postsTo("/api/e2ee/messages");
            assertEquals(1, submits.size());
            Map<?, ?> body = new ObjectMapper().readValue(submits.get(0).body(), Map.class);
            assertEquals(messageId.toString(), body.get("messageRequestId"));
            List<?> envelopes = (List<?>) body.get("envelopes");
            assertEquals(1, envelopes.size());
            Map<?, ?> envelope = (Map<?, ?>) envelopes.get(0);
            assertEquals(Set.of("senderDeviceId", "recipientDeviceId", "envelopeType", "ciphertext"),
                    envelope.keySet());
            assertEquals(aliceServerDevice.toString(), envelope.get("senderDeviceId"));
            assertEquals(bob.serverDeviceId().toString(), envelope.get("recipientDeviceId"));
            assertEquals("PREKEY_INIT", envelope.get("envelopeType"));
            assertFalse(submits.get(0).body().contains("hello alice"));

            List<ScriptedTransport.Call> claims = transport.postsTo(
                    "/api/e2ee/devices/" + bob.serverDeviceId() + "/one-time-prekeys/claim");
            assertEquals(1, claims.size());
            Map<?, ?> claimBody = new ObjectMapper().readValue(claims.get(0).body(), Map.class);
            assertEquals(CryptoTypes.deriveClaimRequestId(
                    messageId, aliceServerDevice, bob.serverDeviceId()).toString(),
                    claimBody.get("requestId"));

            byte[] ciphertext = Base64.getDecoder().decode((String) envelope.get("ciphertext"));
            assertNotEqualPlaintext(ciphertext, "hello alice");
            byte[] plain = bobService(bob.runtime()).decrypt(aliceServerDevice,
                    bob.runtime().deviceId(), CryptoTypes.EnvelopeType.PREKEY_INIT, ciphertext);
            assertEquals("hello alice", E2eePayload.decode(plain).content());
        } finally {
            alice.runtime().close();
            bob.runtime().close();
        }
    }

    @Test
    void multiDeviceFanOutProducesIndependentCiphertexts(@TempDir Path aliceDir, @TempDir Path bobDir,
            @TempDir Path bob2Dir) throws Exception {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 1);
        Party bob2 = newParty(bob2Dir, 2);
        UUID bobUser = UUID.randomUUID();
        UUID aliceServerDevice = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AtomicInteger otpIds = new AtomicInteger(600);
        try {
            ScriptedTransport transport = new ScriptedTransport();
            transport.directoryBody = "[" + bundleJson(bob, bobUser, true, null, null) + ","
                    + bundleJson(bob2, bobUser, true, null, null) + "]";
            for (Party p : List.of(bob, bob2)) {
                transport.claims.put(p.serverDeviceId(), () -> {
                    int otpId = otpIds.getAndIncrement();
                    E2eeRuntime.OneTimePublicKey otp =
                            p.runtime().generateOneTimePrekeys(otpId, 1).get(0);
                    return bundleJson(p, bobUser, true, otpId, base64(otp.publicKey()));
                });
            }
            transport.submitHandler = body -> new HttpResult(201,
                    submitJson(conversationId, List.of(bob.serverDeviceId(), bob2.serverDeviceId()), true));

            E2eeMessageSender.SentMessage sent =
                    sender(transport, alice.runtime(), aliceServerDevice)
                            .send(UUID.randomUUID(), bobUser, "bob", "hello both");

            assertEquals(2, sent.acceptedRecipientDevices().size());
            List<ScriptedTransport.Call> submits = transport.postsTo("/api/e2ee/messages");
            assertEquals(1, submits.size());
            Map<?, ?> body = new ObjectMapper().readValue(submits.get(0).body(), Map.class);
            List<?> envelopes = (List<?>) body.get("envelopes");
            assertEquals(2, envelopes.size());
            String first = (String) ((Map<?, ?>) envelopes.get(0)).get("ciphertext");
            String second = (String) ((Map<?, ?>) envelopes.get(1)).get("ciphertext");
            assertNotEqualStrings(first, second);

            byte[] firstBytes = Base64.getDecoder().decode(first);
            byte[] plain = bobService(bob.runtime()).decrypt(aliceServerDevice,
                    bob.runtime().deviceId(), CryptoTypes.EnvelopeType.valueOf(
                            (String) ((Map<?, ?>) envelopes.get(0)).get("envelopeType")),
                    firstBytes);
            assertEquals("hello both", E2eePayload.decode(plain).content());
        } finally {
            alice.runtime().close();
            bob.runtime().close();
            bob2.runtime().close();
        }
    }

    @Test
    void signedFallbackWithoutOneTimePrekey(@TempDir Path dir, @TempDir Path bobDir) {
        Party alice = newParty(dir, 1);
        Party bob = newParty(bobDir, 1);
        UUID bobUser = UUID.randomUUID();
        UUID aliceServerDevice = UUID.randomUUID();
        try {
            ScriptedTransport transport = new ScriptedTransport();
            transport.directoryBody = "[" + bundleJson(bob, bobUser, false, null, null) + "]";
            transport.claims.put(bob.serverDeviceId(),
                    () -> bundleJson(bob, bobUser, false, null, null));
            transport.submitHandler = body -> new HttpResult(201,
                    submitJson(UUID.randomUUID(), List.of(bob.serverDeviceId()), true));

            E2eeMessageSender.SentMessage sent =
                    sender(transport, alice.runtime(), aliceServerDevice)
                            .send(UUID.randomUUID(), bobUser, "bob", "fallback works");
            assertEquals(List.of("PREKEY_INIT"), sent.envelopeTypes());
        } finally {
            alice.runtime().close();
            bob.runtime().close();
        }
    }

    @Test
    void failedSubmitReplaysByteIdenticalEnvelopes(@TempDir Path aliceDir, @TempDir Path bobDir)
            throws Exception {
        Party alice = newParty(aliceDir, 1);
        Party bob = newParty(bobDir, 1);
        UUID bobUser = UUID.randomUUID();
        UUID aliceServerDevice = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AtomicInteger bobOtpIds = new AtomicInteger(700);
        AtomicInteger submitCalls = new AtomicInteger();
        List<String> submitBodies = new ArrayList<>();
        try {
            ScriptedTransport transport = new ScriptedTransport();
            transport.directoryBody = "[" + bundleJson(bob, bobUser, true, null, null) + "]";
            transport.claims.put(bob.serverDeviceId(), () -> {
                int otpId = bobOtpIds.getAndIncrement();
                E2eeRuntime.OneTimePublicKey otp =
                        bob.runtime().generateOneTimePrekeys(otpId, 1).get(0);
                return bundleJson(bob, bobUser, true, otpId, base64(otp.publicKey()));
            });
            transport.submitHandler = body -> {
                submitBodies.add(body);
                if (submitCalls.getAndIncrement() == 0) {
                    throw new SamvaadApiException(
                            SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1, "down");
                }
                return new HttpResult(201, submitJson(conversationId, List.of(bob.serverDeviceId()), true));
            };

            UUID messageId = UUID.randomUUID();
            E2eeMessageSender sender = sender(transport, alice.runtime(), aliceServerDevice);
            E2eeSendException deferred = assertThrows(E2eeSendException.class,
                    () -> sender.send(messageId, bobUser, "bob", "retry me"));
            assertEquals("DEFERRED_TRANSIENT", deferred.outcomes().get(bob.serverDeviceId()));

            E2eeMessageSender.SentMessage sent =
                    sender.send(messageId, bobUser, "bob", "retry me");
            assertEquals(conversationId, sent.conversationId());
            assertEquals(2, submitBodies.size());
            assertEquals(ciphertextOf(submitBodies.get(0)), ciphertextOf(submitBodies.get(1)));
        } finally {
            alice.runtime().close();
            bob.runtime().close();
        }
    }

    @Test
    void emptyDirectoryFailsWithoutNetworkCalls(@TempDir Path dir) {
        Party alice = newParty(dir, 1);
        try {
            ScriptedTransport transport = new ScriptedTransport();
            E2eeSendException e = assertThrows(E2eeSendException.class,
                    () -> sender(transport, alice.runtime(), UUID.randomUUID())
                            .send(UUID.randomUUID(), UUID.randomUUID(), "bob", "hi"));
            assertTrue(e.getMessage().contains("no active devices"));
            assertEquals(1, transport.calls.size());
        } finally {
            alice.runtime().close();
        }
    }

    @Test
    void unknownRecipientUsernameFailsWithoutNetwork(@TempDir Path dir) {
        Party alice = newParty(dir, 1);
        ScriptedTransport exploding = new ScriptedTransport() {
            @Override
            public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
                throw new AssertionError("no HTTP expected");
            }
        };
        try {
            assertThrows(E2eeSendException.class,
                    () -> sender(exploding, alice.runtime(), UUID.randomUUID())
                            .send(UUID.randomUUID(), UUID.randomUUID(), null, "hi"));
        } finally {
            alice.runtime().close();
        }
    }

    @Test
    void disabledSenderFailsFastWithoutNetwork() {
        ScriptedTransport exploding = new ScriptedTransport() {
            @Override
            public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
                throw new AssertionError("no HTTP expected");
            }
        };
        E2eeMessageSender sender = E2eeMessageSender.disabled("E2EE not ready: testing.");
        E2eeSendException e = assertThrows(E2eeSendException.class,
                () -> sender.send(UUID.randomUUID(), UUID.randomUUID(), "bob", "hi"));
        assertTrue(e.getMessage().contains("not ready"));
        assertTrue(exploding.calls.isEmpty());
    }

    @Test
    void senderHoldsNoRealtimeDependency() {
        for (Field field : E2eeMessageSender.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("ealtime"),
                    "E2EE sender must not reference realtime transports: " + field);
        }
    }

    @Test
    void envelopeMappingUsesLibraryTypeVerbatim() {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        byte[] bytes = {9, 8, 7};
        for (CryptoTypes.EnvelopeType type : CryptoTypes.EnvelopeType.values()) {
            CryptoTypes.OutboundEnvelope envelope = new CryptoTypes.OutboundEnvelope(
                    CryptoTypes.ENVELOPE_FORMAT_VERSION, CryptoTypes.CRYPTO_SUITE, type,
                    UUID.randomUUID(), sender, recipient, bytes);
            var request = E2eeMessageSender.toEnvelopeRequest(envelope);
            assertEquals(sender, request.senderDeviceId());
            assertEquals(recipient, request.recipientDeviceId());
            assertEquals(type.name(), request.envelopeType());
            assertEquals(base64(bytes), request.ciphertext());
        }
    }

    private static void assertNotEqualPlaintext(byte[] ciphertext, String text) {
        assertFalse(new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8).contains(text));
        assertNotEqualStrings(base64(ciphertext),
                Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void assertNotEqualStrings(String a, String b) {
        assertFalse(a.equals(b), "ciphertexts must be independent per device");
    }

    private static String ciphertextOf(String submitBody) throws Exception {
        Map<?, ?> body = new ObjectMapper().readValue(submitBody, Map.class);
        List<?> envelopes = (List<?>) body.get("envelopes");
        return (String) ((Map<?, ?>) envelopes.get(0)).get("ciphertext");
    }
}

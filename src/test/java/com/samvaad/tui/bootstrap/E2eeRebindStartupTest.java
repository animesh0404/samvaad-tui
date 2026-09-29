package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeDeviceApiClientTest;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.FakeHttpTransport;
import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.session.AuthSession;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adopted-device rebind proofs: an already-bound session never prompts,
 * an unbound session prompts once for a hidden recovery code, a valid
 * code binds exactly once with metadata updated only after server
 * success, and every failure degrades without leaking the code.
 */
class E2eeRebindStartupTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final UUID SESSION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID BOB_USER = UUID.randomUUID();
    private static final String CODE = "code-one";

    private static char[] vaultPassword() {
        return "test-only-vault-password".toCharArray();
    }

    private static AuthSession testAuth() {
        return new AuthSession("token", "refresh", SESSION_ID.toString(), 3600, Instant.now(),
                BOB_USER);
    }

    private record ProbedIdentity(String identityB64, int registrationId) {
    }

    private static ProbedIdentity probe(Path dir) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            return new ProbedIdentity(
                    java.util.Base64.getEncoder().encodeToString(runtime.identityPublicKey()),
                    runtime.registrationId());
        } finally {
            runtime.close();
        }
    }

    private static String adoptJson(UUID serverId, ProbedIdentity identity) {
        return "{\"enrollmentState\":\"ENROLLED_ACTIVE\",\"devices\":["
                + E2eeDeviceApiClientTest.deviceJson(
                        serverId, identity.registrationId(), identity.identityB64(),
                        "ACTIVE", 100)
                + "]}";
    }

    private static String captureOutput(Runnable run) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        System.setErr(new PrintStream(captured));
        try {
            run.run();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static long bindPosts(FakeHttpTransport transport) {
        return transport.calls().stream()
                .filter(c -> c.path().endsWith("/bind"))
                .count();
    }

    @Test
    void boundSessionNeverPrompts(@TempDir Path dir) {
        // First launch enrolls fresh (binds SESSION_ID); second launch
        // adopts with the same session: bound, no prompt, ready setup.
        UUID serverId = UUID.randomUUID();
        FakeConsoleIO firstIo = new FakeConsoleIO();
        firstIo.setPassword(vaultPassword());
        FakeHttpTransport first = new FakeHttpTransport();
        first.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        first.addJson(201, "{\"device\":"
                + E2eeDeviceApiClientTest.deviceJson(serverId, 321,
                        java.util.Base64.getEncoder().encodeToString(new byte[] {5, 1, 2, 3}),
                        "ACTIVE", 0)
                + ",\"enrollmentState\":\"NEVER_ENROLLED\",\"recoveryCodes\":[\"a\",\"b\"]}");
        first.addJson(200, E2eeDeviceApiClientTest.deviceJson(serverId, 321,
                java.util.Base64.getEncoder().encodeToString(new byte[] {5, 1, 2, 3}),
                "ACTIVE", 100));
        E2eeStartupInitializer initializer = new E2eeStartupInitializer(firstIo,
                new E2eeDeviceApiClient(first), new E2eeMessageApiClient(first), dir);
        assertNotNull(initializer.initialize(BASE_URL, testAuth()));

        FakeConsoleIO secondIo = new FakeConsoleIO();
        secondIo.setPassword(vaultPassword());
        FakeHttpTransport second = new FakeHttpTransport();
        ProbedIdentity identity = probe(dir);
        second.addJson(200, adoptJson(serverId, identity));
        E2eeSetup setup = new E2eeStartupInitializer(secondIo,
                new E2eeDeviceApiClient(second), new E2eeMessageApiClient(second), dir)
                .initialize(BASE_URL, testAuth());

        assertNotNull(setup);
        assertNotNull(setup.sender());
        assertNotNull(setup.inbox());
        assertEquals(1, secondIo.passwordReads(), "vault read only, no recovery prompt");
    }

    @Test
    void blankInputSkipsWithoutBind(@TempDir Path dir) {
        UUID serverId = UUID.randomUUID();
        ProbedIdentity identity = probe(dir);
        FakeConsoleIO io = new FakeConsoleIO();
        io.addPassword(vaultPassword());
        io.addPassword(new char[0]);
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, adoptJson(serverId, identity));
        E2eeStartupInitializer initializer = new E2eeStartupInitializer(io,
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), dir);

        E2eeSetup setup = initializer.initialize(BASE_URL, testAuth());

        assertNotNull(setup);
        assertEquals(0, bindPosts(transport), "blank input must not call bind");
        E2eeRuntimeFactory.ServerBinding binding =
                E2eeRuntimeFactory.loadServerBinding(dir);
        assertTrue(binding == null || binding.boundSessionIdOrNull() == null
                || !binding.boundSessionIdOrNull().equals(SESSION_ID));
        assertThrows(E2eeSendException.class, () -> setup.sender().send(
                UUID.randomUUID(), UUID.randomUUID(), "bob", "hi"));
        assertNull(setup.inbox());
        assertEquals(2, io.passwordReads());
    }

    @Test
    void validCodeBindsOnceAndBuildsReadySetup(@TempDir Path dir) {
        UUID serverId = UUID.randomUUID();
        ProbedIdentity identity = probe(dir);
        FakeConsoleIO io = new FakeConsoleIO();
        io.addPassword(vaultPassword());
        io.addPassword(CODE.toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, adoptJson(serverId, identity));
        transport.addJson(200, E2eeDeviceApiClientTest.deviceJson(
                serverId, identity.registrationId(), identity.identityB64(), "ACTIVE", 100));
        E2eeStartupInitializer initializer = new E2eeStartupInitializer(io,
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), dir);

        String output = captureOutput(() -> {
            E2eeSetup setup = initializer.initialize(BASE_URL, testAuth());
            assertNotNull(setup);
            assertNotNull(setup.sender());
            assertNotNull(setup.inbox());
        });

        assertEquals(1, bindPosts(transport));
        FakeHttpTransport.Call bind = transport.calls().stream()
                .filter(c -> c.path().endsWith("/bind"))
                .findFirst().orElseThrow();
        assertEquals("/api/e2ee/devices/" + serverId + "/bind", bind.path());
        try {
            Map<?, ?> body = new ObjectMapper().readValue(bind.body(), Map.class);
            assertEquals(Map.of("recoveryCode", CODE), body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        E2eeRuntimeFactory.ServerBinding binding =
                E2eeRuntimeFactory.loadServerBinding(dir);
        assertNotNull(binding);
        assertEquals(serverId, binding.serverDeviceId());
        assertEquals(SESSION_ID, binding.boundSessionIdOrNull());
        ProbedIdentity after = probe(dir);
        assertEquals(identity.identityB64(), after.identityB64(), "vault identity unchanged");
        assertFalse(output.contains(CODE), "recovery code leaked to console");
    }

    @Test
    void bindFailureDegradesWithoutMetadataChange(@TempDir Path dir) {
        UUID serverId = UUID.randomUUID();
        ProbedIdentity identity = probe(dir);
        FakeConsoleIO io = new FakeConsoleIO();
        io.addPassword(vaultPassword());
        io.addPassword(CODE.toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, adoptJson(serverId, identity));
        transport.addJson(403, "denied");
        E2eeStartupInitializer initializer = new E2eeStartupInitializer(io,
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), dir);

        String output = captureOutput(() -> {
            E2eeSetup setup = initializer.initialize(BASE_URL, testAuth());
            assertNotNull(setup);
            assertThrows(E2eeSendException.class, () -> setup.sender().send(
                    UUID.randomUUID(), UUID.randomUUID(), "bob", "hi"));
            assertNull(setup.inbox());
        });

        assertEquals(1, bindPosts(transport));
        E2eeRuntimeFactory.ServerBinding binding =
                E2eeRuntimeFactory.loadServerBinding(dir);
        assertTrue(binding == null || binding.boundSessionIdOrNull() == null
                || !binding.boundSessionIdOrNull().equals(SESSION_ID));
        assertFalse(output.contains(CODE), "recovery code leaked to console");
    }

    @Test
    void stagingFileNeverProvidesTheCode(@TempDir Path dir) throws Exception {
        UUID serverId = UUID.randomUUID();
        ProbedIdentity identity = probe(dir);
        Path staged = dir.resolve("recovery-codes.pending");
        String sentinel = "staged-pending-export\n";
        java.nio.file.Files.writeString(staged, sentinel, StandardCharsets.UTF_8);
        FakeConsoleIO io = new FakeConsoleIO();
        io.addPassword(vaultPassword());
        io.addPassword(new char[0]);
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, adoptJson(serverId, identity));

        E2eeSetup setup = new E2eeStartupInitializer(io,
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), dir)
                .initialize(BASE_URL, testAuth());

        assertNotNull(setup);
        assertNull(setup.inbox());
        assertEquals(0, bindPosts(transport), "staging file must not trigger a bind");
        assertEquals(sentinel,
                java.nio.file.Files.readString(staged, StandardCharsets.UTF_8));
    }

    @Test
    void firstEnrollmentNeverPromptsForRecovery(@TempDir Path dir) {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword(vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        UUID serverId = UUID.randomUUID();
        transport.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        transport.addJson(201, "{\"device\":"
                + E2eeDeviceApiClientTest.deviceJson(serverId, 321,
                        java.util.Base64.getEncoder().encodeToString(new byte[] {5, 1, 2, 3}),
                        "ACTIVE", 0)
                + ",\"enrollmentState\":\"NEVER_ENROLLED\",\"recoveryCodes\":[\"a\",\"b\"]}");
        transport.addJson(200, E2eeDeviceApiClientTest.deviceJson(serverId, 321,
                java.util.Base64.getEncoder().encodeToString(new byte[] {5, 1, 2, 3}),
                "ACTIVE", 100));

        E2eeSetup setup = new E2eeStartupInitializer(io,
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), dir)
                .initialize(BASE_URL, testAuth());

        assertNotNull(setup);
        assertNotNull(setup.inbox());
        assertEquals(1, io.passwordReads(), "vault read only, no recovery prompt");
    }

    @Test
    void reboundSessionFetchesAndDecryptsQueuedMailbox(@TempDir Path aliceDir, @TempDir Path bobDir) {
        // Full restart shape with real crypto: Bob's persisted vault is
        // reopened under a new unbound session, one recovery code rebinds
        // it, and Alice's queued envelope decrypts through the reused
        // persisted state and is acknowledged.
        E2eeRuntime alice = E2eeRuntimeFactory.initialize(aliceDir, vaultPassword());
        E2eeRuntime bobFirst = E2eeRuntimeFactory.initialize(bobDir, vaultPassword());
        UUID aliceUser = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID bobServerId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger otpIds = new java.util.concurrent.atomic.AtomicInteger(500);
        E2eeRuntime.OneTimePublicKey otp =
                bobFirst.generateOneTimePrekeys(otpIds.getAndIncrement(), 1).get(0);
        com.samvaad.e2ee.client.CryptoTypes.RecipientBundle bundle =
                new com.samvaad.e2ee.client.CryptoTypes.RecipientBundle(
                        bobFirst.deviceId(), BOB_USER, 2, bobFirst.registrationId(),
                        bobFirst.identityPublicKey(), bobFirst.signedPrekeyId(),
                        bobFirst.signedPrekeyPublicKey(), bobFirst.signedPrekeySignature(),
                        otp.prekeyId(), otp.publicKey(), bobFirst.kyberPrekeyId(),
                        bobFirst.kyberPublicKey(), bobFirst.kyberSignature());
        java.util.List<com.samvaad.e2ee.client.CryptoTypes.OutboundEnvelope> captured =
                new java.util.ArrayList<>();
        com.samvaad.e2ee.client.SamvaadCryptoService sendService =
                new com.samvaad.e2ee.client.SamvaadCryptoServiceImpl(
                        alice.adapter(), alice.stores(),
                        (peer, claimId) -> bundle,
                        (requestId, envelopes) -> captured.addAll(envelopes));
        com.samvaad.e2ee.client.SamvaadCryptoService.FanoutResult fanout = sendService.sendToDevices(
                UUID.randomUUID(), UUID.randomUUID(),
                E2eePayload.encodeDirectText("queued while offline"),
                java.util.List.of(bundle), java.util.Set.of());
        assertEquals(1, fanout.sentCount());
        String envelopeType = captured.get(0).envelopeType().name();
        String envelopeB64 = java.util.Base64.getEncoder()
                .encodeToString(captured.get(0).envelopeCiphertext());
        String bobIdentity = java.util.Base64.getEncoder()
                .encodeToString(bobFirst.identityPublicKey());
        int bobRegistration = bobFirst.registrationId();
        bobFirst.close();
        alice.close();

        FakeConsoleIO io = new FakeConsoleIO();
        io.addPassword(vaultPassword());
        io.addPassword(CODE.toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"enrollmentState\":\"ENROLLED_ACTIVE\",\"devices\":["
                + E2eeDeviceApiClientTest.deviceJson(
                        bobServerId, bobRegistration, bobIdentity, "ACTIVE", 100)
                + "]}");
        transport.addJson(200, E2eeDeviceApiClientTest.deviceJson(
                bobServerId, bobRegistration, bobIdentity, "ACTIVE", 100));
        transport.addJson(200, "[{\"messageId\":\"" + messageId + "\","
                + "\"conversationId\":\"" + conversationId + "\","
                + "\"sequenceNumber\":4,"
                + "\"senderUserId\":\"" + aliceUser + "\","
                + "\"senderDeviceId\":\"" + alice.deviceId() + "\","
                + "\"envelopeType\":\"" + envelopeType + "\","
                + "\"ciphertext\":\"" + envelopeB64 + "\","
                + "\"serverTimestamp\":\"2026-09-27T10:00:00\"}]");
        transport.addJson(200, "{\"acknowledged\":1}");
        E2eeSetup setup = new E2eeStartupInitializer(io,
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), bobDir)
                .initialize(BASE_URL, testAuth());

        assertNotNull(setup);
        assertNotNull(setup.inbox());
        com.samvaad.tui.model.ConversationStore store =
                new com.samvaad.tui.model.ConversationStore(BOB_USER, java.util.List.of());
        E2eeInboxProcessor.InboxResult result = setup.inbox().process(store);

        assertTrue(result.ackComplete());
        assertEquals(1, result.consumed().size());
        assertEquals("queued while offline", result.consumed().get(0).content());
        assertEquals(1, store.messagesOf(conversationId).size());
        assertEquals(1, transport.calls().stream()
                .filter(c -> c.path().equals("/api/e2ee/mailbox/ack")).count());
        E2eeRuntimeFactory.ServerBinding binding =
                E2eeRuntimeFactory.loadServerBinding(bobDir);
        assertNotNull(binding);
        assertEquals(bobServerId, binding.serverDeviceId());
        assertEquals(SESSION_ID, binding.boundSessionIdOrNull());
    }
}

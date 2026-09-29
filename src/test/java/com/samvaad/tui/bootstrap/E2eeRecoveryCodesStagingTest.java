package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeDeviceApiClientTest;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.FakeHttpTransport;
import com.samvaad.tui.session.AuthSession;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * First-enrollment recovery-code staging proofs: fresh enrollment stages
 * the issued set to a protected file without printing any code, adoption
 * and pending enrollment stage nothing, a pending file is never
 * overwritten, and staging failures degrade without leaking codes.
 */
class E2eeRecoveryCodesStagingTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final List<String> CODES = List.of("alpha-code-1", "alpha-code-2");

    private static char[] vaultPassword() {
        return "test-only-vault-password".toCharArray();
    }

    private static AuthSession testAuth() {
        return new AuthSession("token", "refresh",
                "99999999-9999-9999-9999-999999999999", 3600, Instant.now(),
                UUID.randomUUID());
    }

    private static E2eeStartupInitializer initializer(
            FakeConsoleIO io, FakeHttpTransport transport, Path dir) {
        return new E2eeStartupInitializer(io,
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), dir);
    }

    private static final String IDENTITY_B64 =
            java.util.Base64.getEncoder().encodeToString(new byte[] {5, 1, 2, 3});

    private static String enrollJson(UUID deviceId, String status, boolean withCodes) {
        return "{\"device\":"
                + E2eeDeviceApiClientTest.deviceJson(deviceId, 321, IDENTITY_B64, status, 0)
                + ",\"enrollmentState\":\"NEVER_ENROLLED\""
                + (withCodes ? ",\"recoveryCodes\":[\"alpha-code-1\",\"alpha-code-2\"]}" : "}");
    }

    private static String expectedStaging() {
        return "Samvaad Recovery Codes\n"
                + "======================\n"
                + "\n"
                + "These codes are single-use recovery credentials.\n"
                + "Store this file securely. They are not regenerated automatically.\n"
                + "\n"
                + "alpha-code-1\n"
                + "alpha-code-2\n";
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

    @Test
    void freshEnrollmentStagesCodesWithoutPrinting(@TempDir Path dir) {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword(vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        UUID serverId = UUID.randomUUID();
        transport.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        transport.addJson(201, enrollJson(serverId, "ACTIVE", true));
        transport.addJson(200,
                E2eeDeviceApiClientTest.deviceJson(serverId, 321,
                        IDENTITY_B64, "ACTIVE", 100));

        String[] output = new String[1];
        E2eeSetup[] setup = new E2eeSetup[1];
        output[0] = captureOutput(() ->
                setup[0] = initializer(io, transport, dir).initialize(BASE_URL, testAuth()));

        assertNotNull(setup[0]);
        assertNotNull(setup[0].sender());
        Path staged = dir.resolve("recovery-codes.pending");
        assertTrue(Files.isRegularFile(staged));
        try {
            assertEquals(expectedStaging(), Files.readString(staged, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        for (String code : CODES) {
            assertFalse(output[0].contains(code), "recovery code leaked to console");
        }
    }

    @Test
    void stagingFileIsOwnerOnlyWhereSupported(@TempDir Path dir) throws Exception {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword(vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        UUID serverId = UUID.randomUUID();
        transport.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        transport.addJson(201, enrollJson(serverId, "ACTIVE", true));
        transport.addJson(200,
                E2eeDeviceApiClientTest.deviceJson(serverId, 321,
                        IDENTITY_B64, "ACTIVE", 100));
        initializer(io, transport, dir).initialize(BASE_URL, testAuth());

        Path staged = dir.resolve("recovery-codes.pending");
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(staged);
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    permissions);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX platform: profile-directory fallback applies instead.
        }
    }

    @Test
    void adoptionCreatesNoStagingFile(@TempDir Path dir) throws Exception {
        // First launch enrolls fresh (staging appears); simulate export by
        // deleting it, then launch again: adoption must not recreate it.
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword(vaultPassword());
        FakeHttpTransport first = new FakeHttpTransport();
        UUID serverId = UUID.randomUUID();
        first.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        first.addJson(201, enrollJson(serverId, "ACTIVE", true));
        first.addJson(200,
                E2eeDeviceApiClientTest.deviceJson(serverId, 321, IDENTITY_B64, "ACTIVE", 100));
        assertNotNull(initializer(io, first, dir).initialize(BASE_URL, testAuth()));
        Path staged = dir.resolve("recovery-codes.pending");
        assertTrue(Files.isRegularFile(staged));
        Files.delete(staged);

        // Second launch: same vault identity is adopted (no POST).
        E2eeRuntime probe = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        String identity;
        int registrationId;
        try {
            identity = java.util.Base64.getEncoder()
                    .encodeToString(probe.identityPublicKey());
            registrationId = probe.registrationId();
        } finally {
            probe.close();
        }
        FakeConsoleIO io2 = new FakeConsoleIO();
        io2.setPassword(vaultPassword());
        FakeHttpTransport second = new FakeHttpTransport();
        second.addJson(200, "{\"enrollmentState\":\"ENROLLED_ACTIVE\",\"devices\":["
                + E2eeDeviceApiClientTest.deviceJson(
                        serverId, registrationId, identity, "ACTIVE", 100)
                + "]}");
        assertNotNull(initializer(io2, second, dir).initialize(BASE_URL, testAuth()));
        assertFalse(Files.exists(staged), "adoption must not stage recovery codes");
    }

    @Test
    void pendingEnrollmentStagesNothing(@TempDir Path dir) {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword(vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        transport.addJson(201, enrollJson(UUID.randomUUID(), "PENDING", false));
        E2eeSetup setup = initializer(io, transport, dir).initialize(BASE_URL, testAuth());

        assertNotNull(setup);
        assertFalse(Files.exists(dir.resolve("recovery-codes.pending")));
    }

    @Test
    void existingPendingFileIsNeverOverwritten(@TempDir Path dir) {
        String sentinel = "already-pending-export\n";
        try {
            Files.writeString(dir.resolve("recovery-codes.pending"), sentinel,
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword(vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        UUID serverId = UUID.randomUUID();
        transport.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        transport.addJson(201, enrollJson(serverId, "ACTIVE", true));
        transport.addJson(200,
                E2eeDeviceApiClientTest.deviceJson(serverId, 321, IDENTITY_B64, "ACTIVE", 100));

        String output = captureOutput(() ->
                assertNull(initializer(io, transport, dir).initialize(BASE_URL, testAuth())));

        try {
            assertEquals(sentinel,
                    Files.readString(dir.resolve("recovery-codes.pending"), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        for (String code : CODES) {
            assertFalse(output.contains(code), "recovery code leaked to console");
        }
    }

    @Test
    void stagingDirectoryConflictFailsWithoutLeak(@TempDir Path dir) throws Exception {
        Path blocking = dir.resolve("recovery-codes.pending");
        Files.createDirectory(blocking);
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword(vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        UUID serverId = UUID.randomUUID();
        transport.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        transport.addJson(201, enrollJson(serverId, "ACTIVE", true));
        transport.addJson(200,
                E2eeDeviceApiClientTest.deviceJson(serverId, 321, IDENTITY_B64, "ACTIVE", 100));

        String output = captureOutput(() ->
                assertNull(initializer(io, transport, dir).initialize(BASE_URL, testAuth())));

        assertTrue(Files.isDirectory(blocking), "blocking directory must remain intact");
        try (var stream = Files.list(dir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith("recovery-codes")
                    && !p.equals(blocking)), "no partial staging file may remain");
        }
        for (String code : CODES) {
            assertFalse(output.contains(code), "recovery code leaked to console");
        }
    }
}

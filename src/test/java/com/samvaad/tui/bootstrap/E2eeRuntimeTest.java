package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.e2ee.client.SamvaadCryptoService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline proofs for the local E2EE runtime boundary: first initialization,
 * restart recovery of the same identity, fail-closed wrong-password,
 * corrupt, and inconsistent states, and the private-material boundary.
 *
 * <p>No server, no network, no login: every test runs against temporary
 * directories only.
 */
class E2eeRuntimeTest {

    private static char[] vaultPassword() {
        return "test-only-vault-password".toCharArray();
    }

    @Test
    void freshInitCreatesIdentityAndState(@TempDir Path dir) throws Exception {
        char[] password = vaultPassword();
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, password);
        try {
            assertNotNull(runtime.deviceId());
            assertTrue(runtime.registrationId() >= 1);
            byte[] identity = runtime.identityPublicKey();
            assertEquals(33, identity.length);
            assertFalse(runtime.fingerprint().isBlank());
            assertTrue(runtime.kyberPrekeyId() >= 0);
            assertTrue(runtime.kyberPublicKey().length > 0);
            assertTrue(runtime.kyberSignature().length > 0);
            assertNotNull(runtime.service());
            assertTrue(Files.exists(dir.resolve("device.properties")));
            assertTrue(Files.isRegularFile(dir.resolve("client-crypto-store-v1.json")));
            assertTrue(Files.isRegularFile(dir.resolve("crypto-vault-v1.dat")));
        } finally {
            runtime.close();
        }
        assertZeroed(password);
    }

    @Test
    void restartRecoversSameIdentity(@TempDir Path dir) {
        E2eeRuntime first = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        UUID deviceId;
        byte[] identity;
        String fingerprint;
        byte[] kyberPublicKey;
        try {
            deviceId = first.deviceId();
            identity = first.identityPublicKey();
            fingerprint = first.fingerprint();
            kyberPublicKey = first.kyberPublicKey();
        } finally {
            first.close();
        }

        E2eeRuntime second = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            assertEquals(deviceId, second.deviceId());
            assertArrayEquals(identity, second.identityPublicKey());
            assertEquals(fingerprint, second.fingerprint());
            assertArrayEquals(kyberPublicKey, second.kyberPublicKey());
        } finally {
            second.close();
        }
    }

    @Test
    void wrongPasswordFailsWithoutReplacingIdentity(@TempDir Path dir) {
        E2eeRuntime first = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        byte[] identity;
        try {
            identity = first.identityPublicKey();
        } finally {
            first.close();
        }

        assertThrows(E2eeException.class,
                () -> E2eeRuntimeFactory.initialize(dir, "wrong-password".toCharArray()));

        E2eeRuntime reopened = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            assertArrayEquals(identity, reopened.identityPublicKey());
        } finally {
            reopened.close();
        }
    }

    @Test
    void corruptSnapshotFailsClosedWithoutClobberingState(@TempDir Path dir) throws Exception {
        E2eeRuntime first = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        byte[] identity;
        try {
            identity = first.identityPublicKey();
        } finally {
            first.close();
        }
        Path snapshot = dir.resolve("client-crypto-store-v1.json");
        byte[] backup = Files.readAllBytes(snapshot);
        Files.writeString(snapshot, "{not valid json", StandardCharsets.UTF_8);

        assertThrows(E2eeException.class,
                () -> E2eeRuntimeFactory.initialize(dir, vaultPassword()));

        Files.write(snapshot, backup);
        E2eeRuntime reopened = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            assertArrayEquals(identity, reopened.identityPublicKey());
        } finally {
            reopened.close();
        }
    }

    @Test
    void stateWithoutMetadataFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("orphan.dat"), "unknown state", StandardCharsets.UTF_8);
        assertThrows(E2eeException.class,
                () -> E2eeRuntimeFactory.initialize(dir, vaultPassword()));
    }

    @Test
    void emptyPasswordIsRejected(@TempDir Path dir) {
        assertThrows(E2eeException.class,
                () -> E2eeRuntimeFactory.initialize(dir, new char[0]));
    }

    @Test
    void snapshotCarriesNoPrivateMaterial(@TempDir Path dir) throws Exception {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            String snapshot = Files.readString(
                    dir.resolve("client-crypto-store-v1.json"), StandardCharsets.UTF_8);
            assertTrue(snapshot.contains("\"identityHandle\""));
            String lower = snapshot.toLowerCase();
            assertFalse(lower.contains("privatekey"));
            assertFalse(lower.contains("privatebytes"));
            assertFalse(lower.contains("secretkey"));
            assertFalse(lower.contains("seed"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void offlineServiceSendsNothingWithoutNetwork(@TempDir Path dir) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            SamvaadCryptoService.FanoutResult result = runtime.service().sendToDevices(
                    UUID.randomUUID(), runtime.deviceId(), new byte[] {1}, List.of(), Set.of());
            assertEquals(0, result.sentCount());
        } finally {
            runtime.close();
        }
    }

    @Test
    void interactiveInitPromptsAndZeroesPassword(@TempDir Path dir) {
        FakeConsoleIO io = new FakeConsoleIO();
        char[] live = vaultPassword();
        io.setPassword(live);
        E2eeRuntime runtime = E2eeRuntimeFactory.initializeInteractive(dir, io);
        try {
            assertEquals("Create E2EE vault password: ", io.lastPasswordPrompt());
            assertFalse(runtime.fingerprint().isBlank());
        } finally {
            runtime.close();
        }
        assertZeroed(live);
    }

    @Test
    void interactiveInitWithExistingVaultPromptsToEnter(@TempDir Path dir) {
        E2eeRuntimeFactory.initialize(dir, vaultPassword()).close();
        FakeConsoleIO io = new FakeConsoleIO();
        char[] live = vaultPassword();
        io.setPassword(live);
        E2eeRuntime runtime = E2eeRuntimeFactory.initializeInteractive(dir, io);
        try {
            assertEquals("Enter E2EE vault password: ", io.lastPasswordPrompt());
            assertFalse(runtime.fingerprint().isBlank());
        } finally {
            runtime.close();
        }
        assertZeroed(live);
    }

    @Test
    void interactiveInitWithoutInputFailsClearly(@TempDir Path dir) {
        ConsoleIO unavailable = new ConsoleIO() {
            @Override
            public String readLine(String prompt) {
                throw new IllegalStateException("End of input reached while waiting for input.");
            }

            @Override
            public char[] readPassword(String prompt) {
                throw new IllegalStateException("End of input reached while waiting for input.");
            }
        };
        assertThrows(E2eeException.class,
                () -> E2eeRuntimeFactory.initializeInteractive(dir, unavailable));
    }

    @Test
    void closedRuntimeRefusesUse(@TempDir Path dir) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        runtime.close();
        runtime.close();
        assertThrows(E2eeException.class, runtime::identityPublicKey);
        assertThrows(E2eeException.class, runtime::service);
    }

    private static void assertZeroed(char[] password) {
        for (char c : password) {
            if (c != '\0') {
                throw new AssertionError("password array was not zeroed");
            }
        }
    }
}

package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeDeviceApiClientTest;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.FakeHttpTransport;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup seam proofs: unavailable runtime degrades to null without HTTP,
 * and a pending enrollment yields a disabled sender carrying the reason.
 */
class E2eeStartupInitializerTest {

    @Test
    void emptyPasswordDegradesToNullWithoutHttp(@TempDir Path dir) {
        FakeHttpTransport transport = new FakeHttpTransport();
        E2eeInitializer initializer = new E2eeStartupInitializer(new FakeConsoleIO(),
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), dir);

        assertNull(initializer.initialize("http://localhost:8080", "token"));
        assertTrue(transport.calls().isEmpty());
    }

    @Test
    void pendingEnrollmentYieldsDisabledSender(@TempDir Path dir) {
        UUID serverId = UUID.randomUUID();
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("test-only-vault-password".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}");
        E2eeRuntime probe = E2eeRuntimeFactory.initialize(dir, "test-only-vault-password".toCharArray());
        String identityB64;
        int registrationId;
        try {
            identityB64 = base64(probe.identityPublicKey());
            registrationId = probe.registrationId();
        } finally {
            probe.close();
        }
        transport.addJson(201, "{\"device\":"
                + E2eeDeviceApiClientTest.deviceJson(
                        serverId, registrationId, identityB64, "PENDING", 0)
                + ",\"enrollmentState\":\"NEVER_ENROLLED\"}");
        // Fresh password: the factory zeroes the prompt array after the probe above.
        io.setPassword("test-only-vault-password".toCharArray());
        E2eeInitializer initializer = new E2eeStartupInitializer(io,
                new E2eeDeviceApiClient(transport), new E2eeMessageApiClient(transport), dir);

        E2eeMessageSender sender = initializer.initialize("http://localhost:8080", "token");

        assertNotNull(sender);
        E2eeSendException e = assertThrows(E2eeSendException.class,
                () -> sender.send(UUID.randomUUID(), UUID.randomUUID(), "bob", "hi"));
        assertTrue(e.getMessage().contains("pending approval"));
    }

    private static String base64(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}

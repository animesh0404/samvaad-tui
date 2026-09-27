package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.FakeHttpTransport;
import com.samvaad.tui.api.HttpResult;
import com.samvaad.tui.api.HttpTransport;
import com.samvaad.tui.api.SamvaadApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Enrollment orchestration proofs through the real API client and the real
 * local crypto runtime, with scripted HTTP: first-device bootstrap,
 * pending handling, reconcile-before-create, conflict adoption, ambiguous
 * failure safety, and fail-closed malformed states.
 *
 * <p>Fully offline except for the scripted transport: no server, no login
 * beyond a bearer placeholder.
 */
class E2eeEnrollmentServiceTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "token";

    private static char[] vaultPassword() {
        return "test-only-vault-password".toCharArray();
    }

    @Test
    void fullFirstDeviceFlow(@TempDir Path dir) throws Exception {
        UUID serverId = UUID.randomUUID();
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        String identityB64 = base64(runtime.identityPublicKey());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, emptyListJson());
        transport.addJson(201, enrollJson(deviceJson(serverId, runtime, "ACTIVE", 0), true));
        transport.addJson(200, deviceJson(serverId, runtime, "ACTIVE", 100));
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        E2eeEnrollmentService.EnrolledDevice result;
        byte[] identityBeforeClose;
        try {
            identityBeforeClose = runtime.identityPublicKey();
            result = enrollment.enroll(BASE_URL, TOKEN, runtime, dir);
        } finally {
            runtime.close();
        }

        assertEquals(serverId, result.serverDeviceId());
        assertEquals(1, result.signalDeviceId());
        assertEquals(E2eeEnrollmentState.ACTIVE, result.state());
        assertEquals(List.of("code-one", "code-two"), result.recoveryCodesOrNull());
        assertEquals(100, result.availablePrekeys());

        List<String> paths = transport.calls().stream().map(FakeHttpTransport.Call::path).toList();
        assertEquals(List.of("/api/e2ee/devices", "/api/e2ee/devices",
                "/api/e2ee/devices/" + serverId + "/one-time-prekeys"), paths);

        Map<?, ?> enrollBody = new ObjectMapper().readValue(transport.calls().get(1).body(), Map.class);
        assertEquals(Set.of("registrationId", "deviceIdentityPublicKey", "signedPrekeyId",
                "signedPrekey", "signedPrekeySignature", "kyberPrekeyId", "kyberPrekey",
                "kyberPrekeySignature", "clientPlatform", "clientName", "clientVersion"),
                enrollBody.keySet());
        assertEquals(identityB64, enrollBody.get("deviceIdentityPublicKey"));

        Map<?, ?> uploadBody = new ObjectMapper().readValue(transport.calls().get(2).body(), Map.class);
        List<?> prekeys = (List<?>) uploadBody.get("prekeys");
        assertEquals(100, prekeys.size());
        Set<?> ids = prekeys.stream()
                .map(e -> ((Map<?, ?>) e).get("prekeyId"))
                .collect(Collectors.toSet());
        assertEquals(IntStream.rangeClosed(1, 100).boxed().collect(Collectors.toSet()), ids);

        E2eeRuntimeFactory.ServerBinding binding = E2eeRuntimeFactory.loadServerBinding(dir);
        assertEquals(serverId, binding.serverDeviceId());
        assertEquals(E2eeEnrollmentState.ACTIVE, binding.status());
        assertEquals(100, binding.otpkHighWaterMark());

        String metadata = Files.readString(dir.resolve("device.properties"), StandardCharsets.UTF_8);
        assertFalse(metadata.contains("code-one"));

        E2eeRuntime reopened = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            assertTrue(reopened.hasOneTimePrivate(1));
            assertTrue(reopened.hasOneTimePrivate(100));
            assertArrayEquals(identityBeforeClose, reopened.identityPublicKey());
        } finally {
            reopened.close();
        }
    }

    @Test
    void pendingStaysPendingWithoutUpload(@TempDir Path dir) {
        UUID serverId = UUID.randomUUID();
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, emptyListJson());
        transport.addJson(201, enrollJson(deviceJson(serverId, runtime, "PENDING", 0), false));
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        E2eeEnrollmentService.EnrolledDevice result;
        try {
            result = enrollment.enroll(BASE_URL, TOKEN, runtime, dir);
        } finally {
            runtime.close();
        }

        assertEquals(E2eeEnrollmentState.PENDING_APPROVAL, result.state());
        assertNull(result.recoveryCodesOrNull());
        assertEquals(2, transport.calls().size());
        assertEquals(E2eeEnrollmentState.PENDING_APPROVAL,
                E2eeRuntimeFactory.loadServerBinding(dir).status());
    }

    @Test
    void reconcileAdoptsExistingActiveWithoutNewCalls(@TempDir Path dir) {
        UUID serverId = UUID.randomUUID();
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, listJson(deviceJson(serverId, runtime, "ACTIVE", 100)));
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        E2eeEnrollmentService.EnrolledDevice result;
        try {
            result = enrollment.enroll(BASE_URL, TOKEN, runtime, dir);
        } finally {
            runtime.close();
        }

        assertEquals(serverId, result.serverDeviceId());
        assertEquals(E2eeEnrollmentState.ACTIVE, result.state());
        assertNull(result.recoveryCodesOrNull());
        assertEquals(1, transport.calls().size());
        assertEquals(E2eeEnrollmentState.ACTIVE,
                E2eeRuntimeFactory.loadServerBinding(dir).status());
    }

    @Test
    void reconcileActiveEmptyPoolUploads(@TempDir Path dir) {
        UUID serverId = UUID.randomUUID();
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, listJson(deviceJson(serverId, runtime, "ACTIVE", 0)));
        transport.addJson(200, deviceJson(serverId, runtime, "ACTIVE", 100));
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        E2eeEnrollmentService.EnrolledDevice result;
        try {
            result = enrollment.enroll(BASE_URL, TOKEN, runtime, dir);
        } finally {
            runtime.close();
        }

        assertEquals(100, result.availablePrekeys());
        assertEquals(2, transport.calls().size());
        assertEquals("/api/e2ee/devices/" + serverId + "/one-time-prekeys",
                transport.calls().get(1).path());
    }

    @Test
    void conflictOnEnrollAdoptsViaReconcile(@TempDir Path dir) {
        UUID serverId = UUID.randomUUID();
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, emptyListJson());
        transport.addJson(409, "conflict");
        transport.addJson(200, listJson(deviceJson(serverId, runtime, "ACTIVE", 0)));
        transport.addJson(200, deviceJson(serverId, runtime, "ACTIVE", 100));
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        E2eeEnrollmentService.EnrolledDevice result;
        try {
            result = enrollment.enroll(BASE_URL, TOKEN, runtime, dir);
        } finally {
            runtime.close();
        }

        assertEquals(serverId, result.serverDeviceId());
        assertEquals(E2eeEnrollmentState.ACTIVE, result.state());
        assertEquals(100, result.availablePrekeys());
    }

    @Test
    void ambiguousPostFailureKeepsIdentityAndBinding(@TempDir Path dir) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        byte[] identity = runtime.identityPublicKey();
        runtime.close();
        HttpTransport failing = new HttpTransport() {
            @Override
            public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
                throw new SamvaadApiException(
                        SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1, "down");
            }

            @Override
            public HttpResult put(String baseUrl, String path, String jsonBody, String bearerToken) {
                throw new UnsupportedOperationException();
            }

            @Override
            public HttpResult get(String baseUrl, String pathAndQuery, String bearerToken) {
                return new HttpResult(200, emptyListJson());
            }
        };
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(failing));

        E2eeRuntime attempt = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            SamvaadApiException e = assertThrows(SamvaadApiException.class,
                    () -> enrollment.enroll(BASE_URL, TOKEN, attempt, dir));
            assertEquals(SamvaadApiException.Kind.SERVER_UNAVAILABLE, e.kind());
        } finally {
            attempt.close();
        }

        assertNull(E2eeRuntimeFactory.loadServerBinding(dir));
        E2eeRuntime reopened = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        try {
            assertArrayEquals(identity, reopened.identityPublicKey());
        } finally {
            reopened.close();
        }
    }

    @Test
    void revokedServerDeviceFailsClosed(@TempDir Path dir) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, listJson(
                deviceJson(UUID.randomUUID(), runtime, "REVOKED", 0)));
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        try {
            assertThrows(E2eeException.class,
                    () -> enrollment.enroll(BASE_URL, TOKEN, runtime, dir));
        } finally {
            runtime.close();
        }
    }

    @Test
    void unknownServerStatusFailsClosed(@TempDir Path dir) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, listJson(
                deviceJson(UUID.randomUUID(), runtime, "SUSPENDED", 0)));
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        try {
            assertThrows(E2eeException.class,
                    () -> enrollment.enroll(BASE_URL, TOKEN, runtime, dir));
        } finally {
            runtime.close();
        }
    }

    @Test
    void malformedEnrollResponseFailsWithoutBinding(@TempDir Path dir) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, emptyListJson());
        transport.addJson(201, "{\"device\":null}");
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        try {
            assertThrows(SamvaadApiException.class,
                    () -> enrollment.enroll(BASE_URL, TOKEN, runtime, dir));
        } finally {
            runtime.close();
        }
        assertNull(E2eeRuntimeFactory.loadServerBinding(dir));
    }

    @Test
    void registrationMismatchFailsClosed(@TempDir Path dir) {
        E2eeRuntime runtime = E2eeRuntimeFactory.initialize(dir, vaultPassword());
        String identityB64 = base64(runtime.identityPublicKey());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, listJson(com.samvaad.tui.api.E2eeDeviceApiClientTest.deviceJson(
                UUID.randomUUID(), runtime.registrationId() + 1, identityB64, "ACTIVE", 100)));
        E2eeEnrollmentService enrollment = new E2eeEnrollmentService(new E2eeDeviceApiClient(transport));

        try {
            assertThrows(E2eeException.class,
                    () -> enrollment.enroll(BASE_URL, TOKEN, runtime, dir));
        } finally {
            runtime.close();
        }
    }

    private static String emptyListJson() {
        return "{\"enrollmentState\":\"NEVER_ENROLLED\",\"devices\":[]}";
    }

    private static String listJson(String... devices) {
        return "{\"enrollmentState\":\"ENROLLED_ACTIVE\",\"devices\":["
                + String.join(",", devices) + "]}";
    }

    private static String enrollJson(String device, boolean withCodes) {
        return "{\"device\":" + device + ",\"enrollmentState\":\"NEVER_ENROLLED\""
                + (withCodes ? ",\"recoveryCodes\":[\"code-one\",\"code-two\"]}" : "}");
    }

    private static String deviceJson(UUID serverId, E2eeRuntime runtime, String status, long available) {
        return com.samvaad.tui.api.E2eeDeviceApiClientTest.deviceJson(serverId,
                runtime.registrationId(), base64(runtime.identityPublicKey()), status, available);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}

package com.samvaad.tui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samvaad.tui.api.dto.DeviceListResponse;
import com.samvaad.tui.api.dto.EnrollDeviceRequest;
import com.samvaad.tui.api.dto.EnrollDeviceResponse;
import com.samvaad.tui.api.dto.OneTimePrekeyRequest;
import com.samvaad.tui.api.dto.UploadPrekeysRequest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class E2eeDeviceApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String TOKEN = "token";
    private static final UUID DEVICE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String IDENTITY_B64 =
            Base64.getEncoder().encodeToString(new byte[] {5, 1, 2, 3});

    @Test
    void enrollMapsPublicMaterialAndParsesFirstDevice() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(201, enrollJson("ACTIVE", true));
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        EnrollDeviceResponse response = client.enrollDevice(BASE_URL, TOKEN, enrollRequest());

        assertEquals(DEVICE_ID, response.device().deviceId());
        assertEquals(321, response.device().registrationId());
        assertEquals(1, response.device().signalDeviceId());
        assertEquals("ACTIVE", response.device().status());
        assertEquals(0, response.device().availablePrekeys());
        assertEquals(List.of("code-one", "code-two"), response.recoveryCodes());

        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals("/api/e2ee/devices", call.path());
        assertEquals(TOKEN, call.bearerToken());
        Map<?, ?> body = new ObjectMapper().readValue(call.body(), Map.class);
        assertEquals(321, body.get("registrationId"));
        assertEquals(IDENTITY_B64, body.get("deviceIdentityPublicKey"));
        assertEquals(7, body.get("signedPrekeyId"));
        assertEquals(9, body.get("kyberPrekeyId"));
        assertEquals("TUI", body.get("clientPlatform"));
        assertEquals("samvaad-tui", body.get("clientName"));
        assertEquals("0.1.0", body.get("clientVersion"));
    }

    @Test
    void enrollPendingHasNoRecoveryCodes() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(201, enrollJson("PENDING", false));
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        EnrollDeviceResponse response = client.enrollDevice(BASE_URL, TOKEN, enrollRequest());

        assertEquals("PENDING", response.device().status());
        assertNull(response.recoveryCodes());
    }

    @Test
    void enrollAuthFailure() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "unauthorized");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.enrollDevice(BASE_URL, TOKEN, enrollRequest()));
        assertEquals(SamvaadApiException.Kind.AUTHENTICATION_FAILED, e.kind());
    }

    @Test
    void enrollInvalidRequest() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(400, "bad request");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.enrollDevice(BASE_URL, TOKEN, enrollRequest()));
        assertEquals(SamvaadApiException.Kind.INVALID_REQUEST, e.kind());
    }

    @Test
    void enrollRecoveryRequiredSurfacesReason() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(403, "{\"message\":\"no active devices\",\"reason\":\"E2EE_RECOVERY_REQUIRED\"}");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.enrollDevice(BASE_URL, TOKEN, enrollRequest()));
        assertEquals(SamvaadApiException.Kind.FORBIDDEN, e.kind());
        assertTrue(e.getMessage().contains("recovery"));
    }

    @Test
    void enrollForbiddenWithoutReason() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(403, "{\"message\":\"denied\"}");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.enrollDevice(BASE_URL, TOKEN, enrollRequest()));
        assertEquals(SamvaadApiException.Kind.FORBIDDEN, e.kind());
    }

    @Test
    void enrollConflict() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(409, "conflict");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.enrollDevice(BASE_URL, TOKEN, enrollRequest()));
        assertEquals(SamvaadApiException.Kind.CONFLICT, e.kind());
    }

    @Test
    void enrollMalformedResponse() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(201, "{\"device\":null}");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.enrollDevice(BASE_URL, TOKEN, enrollRequest()));
        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void enrollInvalidJson() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(201, "not json");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        assertThrows(SamvaadApiException.class,
                () -> client.enrollDevice(BASE_URL, TOKEN, enrollRequest()));
    }

    @Test
    void listDevicesPreservesServerOrder() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"enrollmentState\":\"ENROLLED_ACTIVE\",\"devices\":["
                + deviceJson(DEVICE_ID, "ACTIVE", 100) + ","
                + deviceJson(UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        "PENDING", 0)
                + "]}");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        DeviceListResponse response = client.listDevices(BASE_URL, TOKEN);

        assertEquals(2, response.devices().size());
        assertEquals(DEVICE_ID, response.devices().get(0).deviceId());
        assertEquals(100, response.devices().get(0).availablePrekeys());
        assertEquals("ENROLLED_ACTIVE", response.enrollmentState());
        assertEquals("/api/e2ee/devices", transport.lastCall().path());
    }

    @Test
    void listDevicesMalformedWhenDevicesMissing() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"enrollmentState\":\"ENROLLED_ACTIVE\"}");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.listDevices(BASE_URL, TOKEN));
        assertEquals(SamvaadApiException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void uploadMapsHundredPublicPrekeys() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, deviceJson(DEVICE_ID, "ACTIVE", 100));
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        var entries = new java.util.ArrayList<OneTimePrekeyRequest>();
        for (int i = 1; i <= 100; i++) {
            entries.add(new OneTimePrekeyRequest(i, IDENTITY_B64));
        }
        var response = client.uploadOneTimePrekeys(
                BASE_URL, TOKEN, DEVICE_ID, new UploadPrekeysRequest(List.copyOf(entries)));

        assertEquals(100, response.availablePrekeys());
        FakeHttpTransport.Call call = transport.lastCall();
        assertEquals("/api/e2ee/devices/" + DEVICE_ID + "/one-time-prekeys", call.path());
        Map<?, ?> body = new ObjectMapper().readValue(call.body(), Map.class);
        assertEquals(100, ((List<?>) body.get("prekeys")).size());
    }

    @Test
    void uploadNotFound() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(404, "gone");
        E2eeDeviceApiClient client = new E2eeDeviceApiClient(transport);

        SamvaadApiException e = assertThrows(SamvaadApiException.class,
                () -> client.uploadOneTimePrekeys(BASE_URL, TOKEN, DEVICE_ID,
                        new UploadPrekeysRequest(List.of())));
        assertEquals(SamvaadApiException.Kind.NOT_FOUND, e.kind());
    }

    private static EnrollDeviceRequest enrollRequest() {
        return new EnrollDeviceRequest(321, IDENTITY_B64, 7, IDENTITY_B64, IDENTITY_B64,
                9, IDENTITY_B64, IDENTITY_B64, "TUI", "samvaad-tui", "0.1.0");
    }

    private static String enrollJson(String status, boolean withCodes) {
        return "{\"device\":" + deviceJson(DEVICE_ID, status, 0)
                + ",\"enrollmentState\":\"NEVER_ENROLLED\""
                + (withCodes ? ",\"recoveryCodes\":[\"code-one\",\"code-two\"]}" : "}");
    }

    /**
     * Shared server-device JSON builder for enrollment tests in other
     * packages. Field names mirror the verified server contract.
     */
    public static String deviceJson(UUID deviceId, String status, long available) {
        return deviceJson(deviceId, 321, IDENTITY_B64, status, available);
    }

    public static String deviceJson(
            UUID deviceId, int registrationId, String identityB64, String status, long available) {
        return "{\"deviceId\":\"" + deviceId + "\",\"registrationId\":" + registrationId + ","
                + "\"signalDeviceId\":1,\"deviceIdentityPublicKey\":\"" + identityB64 + "\","
                + "\"signedPrekeyId\":7,\"kyberPrekeyId\":9,\"kyberPrekey\":\"" + IDENTITY_B64 + "\","
                + "\"kyberPrekeySignature\":\"" + IDENTITY_B64 + "\",\"clientPlatform\":\"TUI\","
                + "\"clientName\":\"samvaad-tui\",\"clientVersion\":\"0.1.0\","
                + "\"availablePrekeys\":" + available + ","
                + "\"createdAt\":\"2026-09-27T10:00:00\",\"lastActiveAt\":\"2026-09-27T10:00:00\","
                + "\"status\":\"" + status + "\"}";
    }
}

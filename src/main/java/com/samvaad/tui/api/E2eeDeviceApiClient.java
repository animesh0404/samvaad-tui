package com.samvaad.tui.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.samvaad.tui.api.dto.DeviceListResponse;
import com.samvaad.tui.api.dto.E2eeDeviceResponse;
import com.samvaad.tui.api.dto.EnrollDeviceRequest;
import com.samvaad.tui.api.dto.EnrollDeviceResponse;
import com.samvaad.tui.api.dto.UploadPrekeysRequest;
import java.util.UUID;

/**
 * E2EE device endpoints of the Samvaad Server API.
 *
 * <p>Implements only the verified enrollment/provisioning contract:
 * {@code POST /api/e2ee/devices}, {@code GET /api/e2ee/devices}, and
 * {@code PUT /api/e2ee/devices/{deviceId}/one-time-prekeys}. The last-resort
 * Kyber triple is provisioned atomically inside enrollment, so no separate
 * Kyber call exists in this slice.
 *
 * <p>Transport only: this client serializes public enrollment material and
 * parses server responses. It generates no keys, holds no private material,
 * and never logs request bodies. The access token is borrowed per call and
 * never persisted.
 */
public final class E2eeDeviceApiClient {

    static final String ENROLL_PATH = "/api/e2ee/devices";
    static final String LIST_PATH = "/api/e2ee/devices";
    static final String RECOVERY_REQUIRED_REASON = "E2EE_RECOVERY_REQUIRED";

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public E2eeDeviceApiClient(HttpTransport transport) {
        this.transport = transport;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Enrolls the local device's public material.
     *
     * @param baseUrl normalized server base URL
     * @param accessToken current access token
     * @param request public enrollment material (Base64 public bytes only)
     * @return the server-assigned device record, enrollment state, and
     *         one-time recovery codes on first-device bootstrap (null otherwise)
     * @throws SamvaadApiException on authentication, validation, conflict,
     *         forbidden, transport, HTTP, or parse failure
     */
    public EnrollDeviceResponse enrollDevice(
            String baseUrl, String accessToken, EnrollDeviceRequest request) {
        String body = writeBody(request, "Enrollment");
        HttpResult result = transport.post(baseUrl, ENROLL_PATH, body, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Enrollment failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Enrollment");
        if (result.statusCode() != 201) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Enrollment failed (HTTP " + result.statusCode() + ").");
        }
        EnrollDeviceResponse response = parseBody(result.body(), EnrollDeviceResponse.class, "Enrollment");
        if (response.device() == null
                || response.device().deviceId() == null
                || isBlank(response.device().status())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Enrollment failed: malformed server response.");
        }
        return response;
    }

    /**
     * Lists the caller's devices in server order with per-device available
     * prekey counts. Used for reconciliation before creating a device and
     * for verifying provisioning afterwards.
     *
     * @throws SamvaadApiException on authentication, transport, HTTP, or parse failure
     */
    public DeviceListResponse listDevices(String baseUrl, String accessToken) {
        HttpResult result = transport.get(baseUrl, LIST_PATH, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Loading E2EE devices failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Loading E2EE devices");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Loading E2EE devices failed (HTTP " + result.statusCode() + ").");
        }
        DeviceListResponse response = parseBody(result.body(), DeviceListResponse.class, "Loading E2EE devices");
        if (response.devices() == null) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Loading E2EE devices failed: malformed server response.");
        }
        return response;
    }

    /**
     * Uploads exactly {@code PrekeyManager.BATCH_SIZE} (100) one-time public
     * prekeys for an ACTIVE device bound to the caller's session.
     *
     * @return the updated server device record (carries the new available count)
     * @throws SamvaadApiException on authentication, validation, conflict,
     *         forbidden, transport, HTTP, or parse failure
     */
    public E2eeDeviceResponse uploadOneTimePrekeys(
            String baseUrl, String accessToken, UUID serverDeviceId, UploadPrekeysRequest request) {
        String body = writeBody(request, "Prekey upload");
        HttpResult result =
                transport.put(baseUrl, ENROLL_PATH + "/" + serverDeviceId + "/one-time-prekeys", body, accessToken);
        if (result.statusCode() == 401) {
            throw new SamvaadApiException(SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401,
                    "Prekey upload failed: authentication failed. Please log in again.");
        }
        throwIfE2eeError(result, "Prekey upload");
        if (!isSuccess(result.statusCode())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, result.statusCode(),
                    "Prekey upload failed (HTTP " + result.statusCode() + ").");
        }
        E2eeDeviceResponse response =
                parseBody(result.body(), E2eeDeviceResponse.class, "Prekey upload");
        if (response.deviceId() == null || isBlank(response.status())) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    "Prekey upload failed: malformed server response.");
        }
        return response;
    }

    private String writeBody(Object request, String operation) {
        try {
            return mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.HTTP_ERROR, -1,
                    operation + " failed: cannot build request.", e);
        }
    }

    private <T> T parseBody(String body, Class<T> type, String operation) {
        try {
            T value = mapper.readValue(body, type);
            if (value == null) {
                throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                        operation + " failed: malformed server response.");
            }
            return value;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SamvaadApiException(SamvaadApiException.Kind.MALFORMED_RESPONSE, -1,
                    operation + " failed: malformed server response.", e);
        }
    }

    /**
     * Maps the verified E2EE error contract: 400 invalid material/batch,
     * 403 forbidden (including the {@code E2EE_RECOVERY_REQUIRED} reason
     * token), 404 unknown device, 409 conflict/limit/inactive/bound.
     */
    private static void throwIfE2eeError(HttpResult result, String operation) {
        switch (result.statusCode()) {
            case 400 -> throw new SamvaadApiException(SamvaadApiException.Kind.INVALID_REQUEST,
                    400, operation + " failed: invalid request.");
            case 403 -> {
                if (result.body() != null && result.body().contains(RECOVERY_REQUIRED_REASON)) {
                    throw new SamvaadApiException(SamvaadApiException.Kind.FORBIDDEN, 403,
                            operation + " failed: account E2EE recovery is required "
                                    + "(no active devices remain). Enroll with a recovery code.");
                }
                throw new SamvaadApiException(SamvaadApiException.Kind.FORBIDDEN, 403,
                        operation + " failed: forbidden.");
            }
            case 404 -> throw new SamvaadApiException(SamvaadApiException.Kind.NOT_FOUND, 404,
                    operation + " failed: device not found.");
            case 409 -> throw new SamvaadApiException(SamvaadApiException.Kind.CONFLICT, 409,
                    operation + " failed: conflicting device state.");
            default -> {
            }
        }
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

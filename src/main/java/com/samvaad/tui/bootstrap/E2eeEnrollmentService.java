package com.samvaad.tui.bootstrap;

import com.samvaad.e2ee.client.PrekeyManager;
import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.api.dto.DeviceListResponse;
import com.samvaad.tui.api.dto.E2eeDeviceResponse;
import com.samvaad.tui.api.dto.EnrollDeviceRequest;
import com.samvaad.tui.api.dto.EnrollDeviceResponse;
import com.samvaad.tui.api.dto.OneTimePrekeyRequest;
import com.samvaad.tui.api.dto.UploadPrekeysRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated E2EE device enrollment against the Samvaad server.
 *
 * <p>Flow per attempt (all steps use the caller's existing authenticated
 * session; no JWT is persisted here):
 *
 * <ol>
 *   <li>Reconcile: {@code GET /api/e2ee/devices} and match the local
 *       identity public key. A matching server device is adopted (no second
 *       enrollment); a missing one proceeds to enrollment. This is what
 *       makes retries after ambiguous network failures safe.</li>
 *   <li>Enroll: {@code POST /api/e2ee/devices} with public material only,
 *       then persist the server-assigned device id, Signal id, and status.
 *       A {@code 409} conflict reconciles instead of regenerating: the
 *       identity is never replaced because a response was lost.</li>
 *   <li>Provision: when the server reports the device {@code ACTIVE},
 *       generate exactly {@code PrekeyManager.BATCH_SIZE} one-time prekeys
 *       locally (privates sealed in the vault), upload the public halves,
 *       and persist the high-water mark. Uploads are skipped while the
 *       server reports a non-zero pool (atomic server upload plus
 *       reconcile-first makes same-id retries safe).</li>
 * </ol>
 *
 * <p>The last-resort Kyber triple is provisioned atomically inside
 * enrollment (the server requires it there); no separate Kyber call exists
 * in this slice. Pending devices are returned as pending: approval by
 * another active device is a server-side act this client never performs.
 * First-device recovery codes are returned once for user display and are
 * never persisted.
 */
public final class E2eeEnrollmentService {

    private final E2eeDeviceApiClient devices;

    public E2eeEnrollmentService(E2eeDeviceApiClient devices) {
        this.devices = Objects.requireNonNull(devices, "devices");
    }

    /**
     * Outcome of one enrollment attempt. {@code recoveryCodes} is non-null
     * only on a first-device bootstrap and must be displayed once, never
     * stored. {@code sessionBound} is true only when this call created the
     * device: the server binds the calling session at creation, so only a
     * freshly enrolled device can submit or upload with the current
     * session. Adopted devices (reconciled or conflict-adopted) belong to
     * an older dead session until a future slice provides another binding
     * path.
     */
    public record EnrolledDevice(UUID serverDeviceId, int signalDeviceId,
            E2eeEnrollmentState state, List<String> recoveryCodesOrNull, long availablePrekeys,
            boolean sessionBound) {
        public EnrolledDevice {
            Objects.requireNonNull(serverDeviceId, "serverDeviceId");
            Objects.requireNonNull(state, "state");
            recoveryCodesOrNull = recoveryCodesOrNull == null ? null : List.copyOf(recoveryCodesOrNull);
        }
    }

    /**
     * Enrolls (or reconciles) the runtime's local device.
     *
     * @param serverUrl normalized server base URL
     * @param accessToken current access token, borrowed for these calls only
     * @param sessionId current server session id, recorded as the bound
     *                  session on fresh enrollment and compared on adoption
     * @param runtime opened local E2EE runtime (identity is never regenerated here)
     * @param e2eeDir TUI E2EE state directory holding {@code device.properties}
     */
    public EnrolledDevice enroll(String serverUrl, String accessToken, UUID sessionId,
            E2eeRuntime runtime, Path e2eeDir) {
        Objects.requireNonNull(serverUrl, "serverUrl");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(e2eeDir, "e2eeDir");

        byte[] localIdentity = runtime.identityPublicKey();
        int localRegistrationId = runtime.registrationId();
        try {
            E2eeDeviceResponse known =
                    findByIdentity(listDevices(serverUrl, accessToken), localIdentity, localRegistrationId);
            if (known != null) {
                return adopt(serverUrl, accessToken, sessionId, runtime, e2eeDir, known);
            }
            EnrollDeviceResponse enrolled;
            try {
                enrolled = enrollNew(serverUrl, accessToken, runtime);
            } catch (SamvaadApiException e) {
                if (e.kind() != SamvaadApiException.Kind.CONFLICT) {
                    throw e;
                }
                E2eeDeviceResponse conflicted = findByIdentity(
                        listDevices(serverUrl, accessToken), runtime.identityPublicKey(),
                        runtime.registrationId());
                if (conflicted == null) {
                    throw e;
                }
                return adopt(serverUrl, accessToken, sessionId, runtime, e2eeDir, conflicted);
            }
            E2eeRuntimeFactory.noteSessionBinding(e2eeDir, sessionId);
            E2eeDeviceResponse device = enrolled.device();
            E2eeEnrollmentState state = toLocalState(device.status());
            if (state == E2eeEnrollmentState.ACTIVE) {
                long available = uploadInitialPrekeys(serverUrl, accessToken, runtime, e2eeDir, device);
                persistBinding(e2eeDir, device, state, PrekeyManager.BATCH_SIZE);
                return new EnrolledDevice(device.deviceId(), device.signalDeviceId(), state,
                        enrolled.recoveryCodes(), available, true);
            }
            persistBinding(e2eeDir, device, state, 0);
            return new EnrolledDevice(device.deviceId(), device.signalDeviceId(), state, null,
                    device.availablePrekeys(), true);
        } finally {
            Arrays.fill(localIdentity, (byte) 0);
        }
    }

    private EnrolledDevice adopt(String serverUrl, String accessToken, UUID sessionId,
            E2eeRuntime runtime, Path e2eeDir, E2eeDeviceResponse known) {
        E2eeEnrollmentState state = toLocalState(known.status());
        if (state == E2eeEnrollmentState.REVOKED) {
            throw new E2eeException("Local E2EE device is revoked on the server.");
        }
        boolean bound = isBoundTo(e2eeDir, sessionId);
        if (state == E2eeEnrollmentState.ACTIVE && known.availablePrekeys() == 0) {
            long available = uploadInitialPrekeys(serverUrl, accessToken, runtime, e2eeDir, known);
            persistBinding(e2eeDir, known, state, PrekeyManager.BATCH_SIZE);
            return new EnrolledDevice(known.deviceId(), known.signalDeviceId(), state, null,
                    available, bound);
        }
        int mark = state == E2eeEnrollmentState.ACTIVE ? PrekeyManager.BATCH_SIZE : 0;
        persistBinding(e2eeDir, known, state, mark);
        return new EnrolledDevice(known.deviceId(), known.signalDeviceId(), state, null,
                known.availablePrekeys(), bound);
    }

    /**
     * Whether the locally recorded bound session matches the current one.
     * True after a restart that restored the same server session; false
     * after a fresh login created a new session.
     */
    private static boolean isBoundTo(Path e2eeDir, UUID sessionId) {
        E2eeRuntimeFactory.ServerBinding binding = E2eeRuntimeFactory.loadServerBinding(e2eeDir);
        return binding != null && sessionId.equals(binding.boundSessionIdOrNull());
    }

    private EnrollDeviceResponse enrollNew(String serverUrl, String accessToken, E2eeRuntime runtime) {
        EnrollDeviceRequest request = new EnrollDeviceRequest(runtime.registrationId(),
                base64(runtime.identityPublicKey()), runtime.signedPrekeyId(),
                base64(runtime.signedPrekeyPublicKey()), base64(runtime.signedPrekeySignature()),
                runtime.kyberPrekeyId(), base64(runtime.kyberPublicKey()),
                base64(runtime.kyberSignature()), AuthApiClient.CLIENT_PLATFORM,
                AuthApiClient.CLIENT_NAME, AuthApiClient.CLIENT_VERSION);
        return devices.enrollDevice(serverUrl, accessToken, request);
    }

    private long uploadInitialPrekeys(String serverUrl, String accessToken, E2eeRuntime runtime,
            Path e2eeDir, E2eeDeviceResponse device) {
        E2eeRuntimeFactory.ServerBinding binding = E2eeRuntimeFactory.loadServerBinding(e2eeDir);
        int startId = binding == null ? 1 : binding.otpkHighWaterMark() + 1;
        List<E2eeRuntime.OneTimePublicKey> generated =
                runtime.generateOneTimePrekeys(startId, PrekeyManager.BATCH_SIZE);
        List<OneTimePrekeyRequest> entries = new ArrayList<>(generated.size());
        for (E2eeRuntime.OneTimePublicKey key : generated) {
            entries.add(new OneTimePrekeyRequest(key.prekeyId(), base64(key.publicKey())));
        }
        E2eeDeviceResponse updated =
                devices.uploadOneTimePrekeys(serverUrl, accessToken, device.deviceId(),
                        new UploadPrekeysRequest(List.copyOf(entries)));
        if (!updated.deviceId().equals(device.deviceId())) {
            throw new E2eeException("Prekey upload returned a different device.");
        }
        return updated.availablePrekeys();
    }

    private DeviceListResponse listDevices(String serverUrl, String accessToken) {
        return devices.listDevices(serverUrl, accessToken);
    }

    private void persistBinding(Path e2eeDir, E2eeDeviceResponse device, E2eeEnrollmentState state, int mark) {
        E2eeRuntimeFactory.persistServerBinding(
                e2eeDir, device.deviceId(), device.signalDeviceId(), state, mark);
    }

    private static E2eeDeviceResponse findByIdentity(
            DeviceListResponse listing, byte[] identityPublicKey, int localRegistrationId) {
        String wanted = Base64.getEncoder().encodeToString(identityPublicKey);
        for (E2eeDeviceResponse device : listing.devices()) {
            if (device != null && wanted.equals(device.deviceIdentityPublicKey())) {
                if (device.registrationId() != localRegistrationId) {
                    throw new E2eeException("Server device identity matches but registration differs.");
                }
                return device;
            }
        }
        return null;
    }

    /**
     * Maps the authoritative server lifecycle to the local view. Unknown
     * values fail closed: a device must never be mistaken for active.
     */
    static E2eeEnrollmentState toLocalState(String serverStatus) {
        if ("ACTIVE".equals(serverStatus)) {
            return E2eeEnrollmentState.ACTIVE;
        }
        if ("PENDING".equals(serverStatus)) {
            return E2eeEnrollmentState.PENDING_APPROVAL;
        }
        if ("REVOKED".equals(serverStatus)) {
            return E2eeEnrollmentState.REVOKED;
        }
        throw new E2eeException("Unknown E2EE device status from server.");
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}

package com.samvaad.tui.bootstrap;

import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.session.AuthSession;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Production {@link E2eeInitializer}: opens the local device (prompting
 * for the vault password interactively) and enrolls it with the current
 * session. Every failure degrades to plaintext-only with a console
 * warning; login and chat never depend on E2EE. First-device recovery
 * codes are staged to a protected local file for the later Lanterna
 * export flow and are never printed; a staging failure also degrades to
 * plaintext-only without exposing the codes. An adopted ACTIVE device
 * whose session is unbound offers one hidden recovery-code prompt to
 * rebind it; anything else degrades as usual.
 */
public final class E2eeStartupInitializer implements E2eeInitializer {

    private final ConsoleIO io;
    private final E2eeDeviceApiClient devices;
    private final E2eeMessageApiClient messages;
    private final Path e2eeDir;

    public E2eeStartupInitializer(ConsoleIO io, E2eeDeviceApiClient devices,
            E2eeMessageApiClient messages, Path e2eeDir) {
        this.io = Objects.requireNonNull(io, "io");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.e2eeDir = Objects.requireNonNull(e2eeDir, "e2eeDir");
    }

    @Override
    public E2eeSetup initialize(String serverUrl, AuthSession auth) {
        E2eeRuntime runtime;
        try {
            runtime = E2eeRuntimeFactory.initializeInteractive(e2eeDir, io);
        } catch (RuntimeException e) {
            System.out.println("Warning: E2EE unavailable (" + safeMessage(e) + "). Plaintext only.");
            return null;
        }
        E2eeEnrollmentService.EnrolledDevice enrolled;
        try {
            enrolled = new E2eeEnrollmentService(devices)
                    .enroll(serverUrl, auth.accessToken(), UUID.fromString(auth.sessionId()),
                            runtime, e2eeDir);
        } catch (RuntimeException e) {
            System.out.println("Warning: E2EE enrollment unavailable ("
                    + safeMessage(e) + "). Plaintext only.");
            runtime.close();
            return null;
        }
        if (enrolled.recoveryCodesOrNull() != null && !enrolled.recoveryCodesOrNull().isEmpty()) {
            try {
                E2eeRuntimeFactory.stageRecoveryCodes(e2eeDir, enrolled.recoveryCodesOrNull());
            } catch (RuntimeException e) {
                System.out.println("Warning: E2EE recovery staging failed ("
                        + safeMessage(e) + "). Plaintext only.");
                runtime.close();
                return null;
            }
        }
        if (enrolled.state() == E2eeEnrollmentState.ACTIVE && enrolled.sessionBound()) {
            System.out.println(
                    "E2EE ready (device " + enrolled.serverDeviceId() + "). Ctrl+E sends encrypted.");
            return new E2eeSetup(
                    E2eeMessageSender.ready(devices, messages, serverUrl, auth.accessToken(),
                            runtime, enrolled.serverDeviceId()),
                    E2eeInboxProcessor.ready(messages, serverUrl, auth.accessToken(), runtime),
                    pendingExportOrNull());
        }
        if (enrolled.state() == E2eeEnrollmentState.ACTIVE && !enrolled.sessionBound()) {
            E2eeSetup rebound = tryRebind(serverUrl, auth, runtime, enrolled.serverDeviceId());
            if (rebound != null) {
                return rebound;
            }
        }
        String reason = enrolled.state() == E2eeEnrollmentState.PENDING_APPROVAL
                ? "device pending approval by another device"
                : "current session is not bound to the enrolled device";
        System.out.println("Warning: E2EE not ready (" + reason + "). Plaintext only.");
        return new E2eeSetup(E2eeMessageSender.disabled("E2EE not ready: " + reason + "."),
                null, pendingExportOrNull());
    }

    /**
     * Pending first-enrollment export for this launch, if a staged file
     * exists (fresh enrollment staged it moments ago, or an earlier
     * launch left it unexported). Never creates anything here.
     */
    private RecoveryCodesExport pendingExportOrNull() {
        RecoveryCodesExport export = new RecoveryCodesExport(e2eeDir);
        return export.hasPending() ? export : null;
    }

    /**
     * One-shot recovery-code rebind of an adopted ACTIVE device to the
     * current session. Prompts once with hidden input; blank skips.
     * Local binding metadata is written only after the server confirms
     * the bind. Any failure (wrong code, conflict, transport, local
     * persist) degrades to the standard disabled path without leaking
     * the code and without creating a device.
     *
     * @return the ready setup, or null to fall through to disabled
     */
    private E2eeSetup tryRebind(String serverUrl, AuthSession auth, E2eeRuntime runtime,
            UUID serverDeviceId) {
        System.out.println("Existing E2EE device found, but this login session is not bound to it.");
        char[] code;
        try {
            code = new ConsolePrompter(io).promptRecoveryCode();
        } catch (RuntimeException e) {
            return null;
        }
        if (code == null) {
            return null;
        }
        try {
            if (code.length == 0) {
                return null;
            }
            devices.bindDevice(serverUrl, auth.accessToken(), serverDeviceId, new String(code));
        } catch (RuntimeException e) {
            System.out.println("Warning: E2EE device rebind failed. Plaintext only.");
            return null;
        } finally {
            Arrays.fill(code, '\0');
        }
        try {
            E2eeRuntimeFactory.noteSessionBinding(e2eeDir, UUID.fromString(auth.sessionId()));
        } catch (RuntimeException e) {
            System.out.println("Warning: E2EE device rebind failed. Plaintext only.");
            return null;
        }
        System.out.println(
                "E2EE device rebound (device " + serverDeviceId + "). Ctrl+E sends encrypted.");
        return new E2eeSetup(
                E2eeMessageSender.ready(devices, messages, serverUrl, auth.accessToken(),
                        runtime, serverDeviceId),
                E2eeInboxProcessor.ready(messages, serverUrl, auth.accessToken(), runtime),
                pendingExportOrNull());
    }

    private static String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}

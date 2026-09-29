package com.samvaad.tui.bootstrap;

import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.session.AuthSession;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Production {@link E2eeInitializer}: opens the local device (prompting
 * for the vault password interactively) and enrolls it with the current
 * session. Every failure degrades to plaintext-only with a console
 * warning; login and chat never depend on E2EE. Recovery codes from a
 * first-device bootstrap are printed once and never stored.
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
        if (enrolled.recoveryCodesOrNull() != null) {
            System.out.println("E2EE recovery codes (first device only, shown once — store them safely):");
            for (String code : enrolled.recoveryCodesOrNull()) {
                System.out.println("  " + code);
            }
        }
        if (enrolled.state() == E2eeEnrollmentState.ACTIVE && enrolled.sessionBound()) {
            System.out.println(
                    "E2EE ready (device " + enrolled.serverDeviceId() + "). Ctrl+E sends encrypted.");
            return new E2eeSetup(
                    E2eeMessageSender.ready(devices, messages, serverUrl, auth.accessToken(),
                            runtime, enrolled.serverDeviceId()),
                    E2eeInboxProcessor.ready(messages, serverUrl, auth.accessToken(), runtime));
        }
        String reason = enrolled.state() == E2eeEnrollmentState.PENDING_APPROVAL
                ? "device pending approval by another device"
                : "current session is not bound to the enrolled device";
        System.out.println("Warning: E2EE not ready (" + reason + "). Plaintext only.");
        return new E2eeSetup(E2eeMessageSender.disabled("E2EE not ready: " + reason + "."),
                null);
    }

    private static String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}

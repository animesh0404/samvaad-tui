package com.samvaad.tui.bootstrap;

import java.util.Objects;

/**
 * Prompts for values missing from the command line.
 *
 * <p>Only checks for blank input here; structural validation
 * (e.g. URL scheme) lives in the config layer.
 */
public final class ConsolePrompter {

    private final ConsoleIO io;

    public ConsolePrompter(ConsoleIO io) {
        this.io = Objects.requireNonNull(io, "io");
    }

    public String promptServerUrl(String current) {
        if (isPresent(current)) {
            return current.trim();
        }
        while (true) {
            String value = io.readLine("Server URL: ");
            if (isPresent(value)) {
                return value.trim();
            }
            System.out.println("Server URL must not be empty.");
        }
    }

    public String promptUsername(String current) {
        if (isPresent(current)) {
            return current.trim();
        }
        while (true) {
            String value = io.readLine("Username: ");
            if (isPresent(value)) {
                return value.trim();
            }
            System.out.println("Username must not be empty.");
        }
    }

    public char[] promptPassword() {
        return io.readPassword("Password: ");
    }

    /**
     * Prompts to unlock an already-created E2EE vault. Like the login
     * password, the value is never echoed when a real console is
     * available and is never stored; the caller zeroes the array after use.
     */
    public char[] promptE2eeVaultPassword() {
        return io.readPassword("Enter E2EE vault password: ");
    }

    /**
     * Prompts to create the E2EE vault passphrase on first-time vault
     * initialization. Same hidden-input handling as
     * {@link #promptE2eeVaultPassword}.
     */
    public char[] promptNewE2eeVaultPassword() {
        return io.readPassword("Create E2EE vault password: ");
    }

    /**
     * Prompts once for a single recovery code to rebind an adopted
     * device. Hidden input like passwords; blank means "skip recovery
     * for now". Single attempt only — no retry loop. The caller zeroes
     * the array after use and never logs it.
     */
    public char[] promptRecoveryCode() {
        return io.readPassword("Recovery code (Enter to skip): ");
    }

    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

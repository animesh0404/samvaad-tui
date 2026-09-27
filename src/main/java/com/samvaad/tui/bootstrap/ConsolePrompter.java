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
     * Prompts for the E2EE vault passphrase. Like the login password, the
     * value is never echoed when a real console is available and is never
     * stored; the caller zeroes the array after use.
     */
    public char[] promptE2eeVaultPassword() {
        return io.readPassword("E2EE vault password: ");
    }

    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

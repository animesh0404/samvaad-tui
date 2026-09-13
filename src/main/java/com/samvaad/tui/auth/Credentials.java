package com.samvaad.tui.auth;

import java.util.Arrays;

/**
 * Username with a mutable password held as {@code char[]} so it can be
 * zeroed after use. Never log or print the password.
 */
public record Credentials(String username, char[] password) {

    public Credentials {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be empty.");
        }
        if (password == null) {
            throw new IllegalArgumentException("Password must not be null.");
        }
    }

    /**
     * Zeroes the held password.
     */
    public void clear() {
        Arrays.fill(password, '\0');
    }
}

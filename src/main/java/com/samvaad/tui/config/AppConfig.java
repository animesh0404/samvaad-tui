package com.samvaad.tui.config;

/**
 * Validated, normalized application configuration for one run.
 *
 * @param serverUrl normalized server base URL without trailing slash
 * @param username trimmed username
 */
public record AppConfig(String serverUrl, String username) {

    public AppConfig {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("Server URL must not be empty.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be empty.");
        }
    }
}

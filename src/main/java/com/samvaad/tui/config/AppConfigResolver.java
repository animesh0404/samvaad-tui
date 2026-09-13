package com.samvaad.tui.config;

/**
 * Merges raw CLI/prompted values into a validated {@link AppConfig}.
 */
public final class AppConfigResolver {

    private AppConfigResolver() {
    }

    public static AppConfig resolve(String serverUrl, String username) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("Server URL must not be empty. "
                    + "Provide --server or enter a URL starting with http:// or https://.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be empty. "
                    + "Provide --username or enter a username.");
        }
        String normalizedUrl = normalizeServerUrl(serverUrl);
        String normalizedUsername = username.trim();
        return new AppConfig(normalizedUrl, normalizedUsername);
    }

    static String normalizeServerUrl(String raw) {
        String trimmed = raw.trim();
        String lower = trimmed.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "Invalid server URL '" + trimmed + "'. Must start with http:// or https://.");
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.equalsIgnoreCase("http://") || trimmed.equalsIgnoreCase("https://")) {
            throw new IllegalArgumentException("Invalid server URL '" + raw.trim() + "'. Missing host.");
        }
        return trimmed;
    }
}

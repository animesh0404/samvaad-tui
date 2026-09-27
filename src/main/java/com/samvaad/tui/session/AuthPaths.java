package com.samvaad.tui.session;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

/**
 * Platform-appropriate resolution of the TUI-owned authentication-state
 * directory. Intentionally mirrors the E2EE path logic without sharing it:
 * auth state and crypto state are separate trust domains that must never
 * be stored together.
 *
 * <p>Layout:
 *
 * <pre>
 * &lt;base&gt;/samvaad/auth/
 * </pre>
 *
 * <p>where {@code &lt;base&gt;} is {@code %APPDATA%} on Windows and
 * {@code $XDG_DATA_HOME} (else {@code ~/.local/share}) on other platforms.
 */
public final class AuthPaths {

    private AuthPaths() {
    }

    /**
     * Default authentication-state directory for this user on this platform.
     */
    public static Path defaultAuthDir() {
        return defaultBaseDir().resolve("samvaad").resolve("auth");
    }

    static Path defaultBaseDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData);
            }
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg);
        }
        return userHome().resolve(".local").resolve("share");
    }

    private static Path userHome() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("Cannot resolve auth state directory: user.home is not set.");
        }
        return Paths.get(Objects.requireNonNull(home));
    }
}

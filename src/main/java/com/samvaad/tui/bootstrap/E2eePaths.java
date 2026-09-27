package com.samvaad.tui.bootstrap;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

/**
 * Platform-appropriate resolution of the TUI-owned E2EE state directory.
 *
 * <p>Only client-owned cryptographic state lives here (encrypted vault,
 * crypto-store snapshot, non-secret device metadata). Authentication
 * tokens, session state, and all other domain/UI state remain memory-only
 * per ADR-0005. Nothing under this directory is ever read from or written
 * to the source repository, the build directory, or {@code /tmp}.
 *
 * <p>Layout:
 *
 * <pre>
 * &lt;base&gt;/samvaad/e2ee/
 * </pre>
 *
 * <p>where {@code &lt;base&gt;} is {@code %APPDATA%} on Windows and
 * {@code $XDG_DATA_HOME} (else {@code ~/.local/share}) on other platforms.
 * Path/OS details stay inside this class; the domain layer only sees a
 * {@link Path}.
 */
public final class E2eePaths {

    private E2eePaths() {
    }

    /**
     * Default E2EE state directory for this user on this platform.
     */
    public static Path defaultE2eeDir() {
        return defaultBaseDir().resolve("samvaad").resolve("e2ee");
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
            throw new E2eeException("Cannot resolve E2EE state directory: user.home is not set.");
        }
        return Paths.get(Objects.requireNonNull(home));
    }
}

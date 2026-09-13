package com.samvaad.tui.session;

import com.samvaad.tui.config.AppConfig;
import java.util.Objects;

/**
 * Client-side session state for the application lifetime.
 *
 * <p>Phase 1 only tracks the resolved config and an
 * {@link AuthStatus}. Tokens arrive in Phase 2.
 */
public final class SessionState {

    public enum AuthStatus {
        UNAUTHENTICATED
    }

    private final AppConfig config;
    private final AuthStatus authStatus;

    private SessionState(AppConfig config, AuthStatus authStatus) {
        this.config = config;
        this.authStatus = authStatus;
    }

    public static SessionState unauthenticated(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return new SessionState(config, AuthStatus.UNAUTHENTICATED);
    }

    public AppConfig config() {
        return config;
    }

    public AuthStatus authStatus() {
        return authStatus;
    }
}

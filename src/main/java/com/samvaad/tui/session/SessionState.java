package com.samvaad.tui.session;

import com.samvaad.tui.api.dto.AuthResponse;
import com.samvaad.tui.config.AppConfig;
import java.util.Objects;
import java.util.Optional;

/**
 * Client-side session state for the application lifetime.
 *
 * <p>Immutable: token replacement and cleanup return new instances so
 * cleared secrets become unreachable. Tokens live in memory only.
 */
public final class SessionState {

    public enum AuthStatus {
        UNAUTHENTICATED,
        AUTHENTICATED
    }

    private final AppConfig config;
    private final AuthStatus authStatus;
    private final AuthSession authSession;

    private SessionState(AppConfig config, AuthStatus authStatus, AuthSession authSession) {
        this.config = config;
        this.authStatus = authStatus;
        this.authSession = authSession;
    }

    public static SessionState unauthenticated(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return new SessionState(config, AuthStatus.UNAUTHENTICATED, null);
    }

    public static SessionState authenticated(AppConfig config, AuthSession authSession) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(authSession, "authSession");
        return new SessionState(config, AuthStatus.AUTHENTICATED, authSession);
    }

    /**
     * Returns a new authenticated state holding the tokens from the latest
     * server authentication/refresh response.
     */
    public SessionState withRefreshedTokens(AuthResponse response) {
        Objects.requireNonNull(response, "response");
        if (authStatus != AuthStatus.AUTHENTICATED) {
            throw new IllegalStateException("Cannot refresh tokens on an unauthenticated session.");
        }
        return new SessionState(config, AuthStatus.AUTHENTICATED, AuthSession.from(response));
    }

    /**
     * Returns a new unauthenticated state, dropping all token material.
     */
    public SessionState cleared() {
        return new SessionState(config, AuthStatus.UNAUTHENTICATED, null);
    }

    public AppConfig config() {
        return config;
    }

    public AuthStatus authStatus() {
        return authStatus;
    }

    public Optional<AuthSession> authSession() {
        return Optional.ofNullable(authSession);
    }
}

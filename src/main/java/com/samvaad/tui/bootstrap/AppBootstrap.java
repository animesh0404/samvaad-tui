package com.samvaad.tui.bootstrap;

import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.api.dto.AuthResponse;
import com.samvaad.tui.auth.Credentials;
import com.samvaad.tui.cli.CliOptions;
import com.samvaad.tui.config.AppConfig;
import com.samvaad.tui.config.AppConfigResolver;
import com.samvaad.tui.session.AuthSession;
import com.samvaad.tui.session.SessionState;
import com.samvaad.tui.ui.TuiException;
import com.samvaad.tui.ui.TuiLauncher;
import java.util.Arrays;
import java.util.Objects;

/**
 * Phase 3 startup flow: resolve config, log in, enter the fullscreen TUI,
 * then revoke the server session via logout and clear all local secrets.
 */
public final class AppBootstrap {

    private final ConsoleIO io;
    private final AuthApiClient authApi;
    private final TuiLauncher tui;

    public AppBootstrap(ConsoleIO io, AuthApiClient authApi, TuiLauncher tui) {
        this.io = Objects.requireNonNull(io, "io");
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.tui = Objects.requireNonNull(tui, "tui");
    }

    public int run(CliOptions options) {
        Objects.requireNonNull(options, "options");
        ConsolePrompter prompter = new ConsolePrompter(io);
        AppConfig config;
        try {
            String serverUrl = prompter.promptServerUrl(options.serverUrl());
            String username = prompter.promptUsername(options.username());
            config = AppConfigResolver.resolve(serverUrl, username);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        char[] password = prompter.promptPassword();
        Credentials credentials = new Credentials(config.username(), password);
        AuthResponse response;
        try {
            response = authApi.login(config.serverUrl(), credentials.username(), credentials.password());
        } catch (SamvaadApiException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } finally {
            credentials.clear();
            Arrays.fill(password, '\0');
        }

        SessionState session = SessionState.authenticated(config, AuthSession.from(response));
        AuthSession auth = session.authSession().orElseThrow();
        System.out.println("Server: " + session.config().serverUrl());
        System.out.println("Username: " + session.config().username());
        System.out.println("Session: " + auth.sessionId());
        System.out.println("Authenticated: yes (expires in " + auth.expiresInSeconds() + " seconds)");
        int tuiExit = 0;
        try {
            tui.launch(config.username(), config.serverUrl());
        } catch (TuiException e) {
            System.err.println("Error: " + e.getMessage());
            tuiExit = 1;
        }
        try {
            authApi.logout(config.serverUrl(), auth.accessToken());
            System.out.println("Logged out. Server session revoked.");
        } catch (SamvaadApiException e) {
            System.err.println("Warning: logout failed (" + e.getMessage() + "). Local session cleared.");
        } finally {
            session = session.cleared();
        }
        return tuiExit;
    }
}

package com.samvaad.tui.bootstrap;

import com.samvaad.tui.auth.Credentials;
import com.samvaad.tui.cli.CliOptions;
import com.samvaad.tui.config.AppConfig;
import com.samvaad.tui.config.AppConfigResolver;
import com.samvaad.tui.session.SessionState;
import java.util.Arrays;
import java.util.Objects;

/**
 * Phase 1 startup flow: merge CLI options with interactive prompts,
 * validate into {@link AppConfig}, prompt securely for the password,
 * and print a sanitized summary. Makes no server calls.
 */
public final class AppBootstrap {

    private final ConsoleIO io;

    public AppBootstrap(ConsoleIO io) {
        this.io = Objects.requireNonNull(io, "io");
    }

    public int run(CliOptions options) {
        Objects.requireNonNull(options, "options");
        ConsolePrompter prompter = new ConsolePrompter(io);
        try {
            String serverUrl = prompter.promptServerUrl(options.serverUrl());
            String username = prompter.promptUsername(options.username());
            AppConfig config = AppConfigResolver.resolve(serverUrl, username);

            char[] password = prompter.promptPassword();
            Credentials credentials = new Credentials(config.username(), password);
            // Phase 1: prove the password was collected without ever displaying it,
            // then immediately clear it. No authentication happens yet.
            boolean collected = credentials.password().length >= 0;
            credentials.clear();
            Arrays.fill(password, '\0');

            SessionState session = SessionState.unauthenticated(config);
            System.out.println("Server: " + session.config().serverUrl());
            System.out.println("Username: " + session.config().username());
            System.out.println("Password collected: " + collected + " (cleared from memory)");
            System.out.println("Authenticated: no (Phase 1 bootstrap only - no server call made)");
            return 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}

package com.samvaad.tui.cli;

import com.samvaad.tui.bootstrap.AppBootstrap;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Picocli command defining the {@code samvaad-tui} CLI surface.
 *
 * <p>Phase 1 only: collects {@code --server} and {@code --username},
 * delegates prompting/validation to {@link AppBootstrap}.
 */
@Command(
        name = "samvaad-tui",
        mixinStandardHelpOptions = true,
        version = "samvaad-tui 0.1.0",
        description = "Thin terminal client for the Samvaad Server. "
                + "Prompts for any missing values and always prompts securely for the password.")
public final class SamvaadTuiCommand implements Callable<Integer> {

    @Option(names = "--server", paramLabel = "URL", description = "Samvaad server base URL, e.g. http://localhost:8080")
    private String serverUrl;

    @Option(names = "--username", paramLabel = "USERNAME", description = "Username to log in as")
    private String username;

    private final AppBootstrap bootstrap;

    public SamvaadTuiCommand(AppBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public Integer call() {
        return bootstrap.run(new CliOptions(serverUrl, username));
    }

    String getServerUrl() {
        return serverUrl;
    }

    String getUsername() {
        return username;
    }
}

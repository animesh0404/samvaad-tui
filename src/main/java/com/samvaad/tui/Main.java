package com.samvaad.tui;

import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.ConversationApiClient;
import com.samvaad.tui.api.JdkHttpTransport;
import com.samvaad.tui.bootstrap.AppBootstrap;
import com.samvaad.tui.bootstrap.SystemConsoleIO;
import com.samvaad.tui.cli.SamvaadTuiCommand;
import com.samvaad.tui.ui.TuiApp;
import picocli.CommandLine;

/**
 * Application entry point for the Samvaad TUI client.
 *
 * <p>Phase 4: CLI bootstrap, server authentication, server-backed
 * conversations and message history in the fullscreen TUI.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        JdkHttpTransport transport = new JdkHttpTransport();
        AppBootstrap bootstrap = new AppBootstrap(
                new SystemConsoleIO(),
                new AuthApiClient(transport),
                new ConversationApiClient(transport),
                new TuiApp());
        SamvaadTuiCommand command = new SamvaadTuiCommand(bootstrap);
        int exitCode = new CommandLine(command).execute(args);
        System.exit(exitCode);
    }
}

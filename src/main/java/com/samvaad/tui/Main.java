package com.samvaad.tui;

import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.JdkHttpTransport;
import com.samvaad.tui.bootstrap.AppBootstrap;
import com.samvaad.tui.bootstrap.SystemConsoleIO;
import com.samvaad.tui.cli.SamvaadTuiCommand;
import picocli.CommandLine;

/**
 * Application entry point for the Samvaad TUI client.
 *
 * <p>Phase 2: CLI bootstrap plus server authentication
 * (login, session, logout). No TUI yet.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        AppBootstrap bootstrap = new AppBootstrap(
                new SystemConsoleIO(), new AuthApiClient(new JdkHttpTransport()));
        SamvaadTuiCommand command = new SamvaadTuiCommand(bootstrap);
        int exitCode = new CommandLine(command).execute(args);
        System.exit(exitCode);
    }
}

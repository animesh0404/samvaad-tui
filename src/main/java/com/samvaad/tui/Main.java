package com.samvaad.tui;

import com.samvaad.tui.bootstrap.AppBootstrap;
import com.samvaad.tui.bootstrap.SystemConsoleIO;
import com.samvaad.tui.cli.SamvaadTuiCommand;
import picocli.CommandLine;

/**
 * Application entry point for the Samvaad TUI client.
 *
 * <p>Phase 1 only: parses CLI options and runs the bootstrap flow
 * (prompt for missing values, print a sanitized summary). No server calls.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SamvaadTuiCommand command = new SamvaadTuiCommand(new AppBootstrap(new SystemConsoleIO()));
        int exitCode = new CommandLine(command).execute(args);
        System.exit(exitCode);
    }
}

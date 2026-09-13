package com.samvaad.tui.bootstrap;

import java.io.Console;
import java.io.PrintStream;
import java.util.Scanner;

/**
 * {@link ConsoleIO} backed by {@link System#console()} when available,
 * with a {@link Scanner} fallback for IDE/Gradle runs where no console exists.
 *
 * <p>The fallback cannot hide input; it prints a one-time warning to stderr.
 */
public final class SystemConsoleIO implements ConsoleIO {

    private final Scanner scanner;
    private final PrintStream out;
    private final PrintStream err;
    private boolean fallbackWarningShown;

    public SystemConsoleIO() {
        this(new Scanner(System.in), System.out, System.err);
    }

    SystemConsoleIO(Scanner scanner, PrintStream out, PrintStream err) {
        this.scanner = scanner;
        this.out = out;
        this.err = err;
    }

    @Override
    public String readLine(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readLine("%s", prompt);
        }
        out.print(prompt);
        out.flush();
        return nextLine();
    }

    @Override
    public char[] readPassword(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readPassword("%s", prompt);
        }
        if (!fallbackWarningShown) {
            err.println("Warning: no console available; password input will be visible.");
            fallbackWarningShown = true;
        }
        out.print(prompt);
        out.flush();
        return nextLine().toCharArray();
    }

    private String nextLine() {
        if (!scanner.hasNextLine()) {
            throw new IllegalStateException("End of input reached while waiting for input.");
        }
        return scanner.nextLine();
    }
}

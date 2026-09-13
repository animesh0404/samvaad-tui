package com.samvaad.tui.bootstrap;

/**
 * Minimal console abstraction so prompting logic is unit-testable
 * without depending directly on {@link System#console()}.
 */
public interface ConsoleIO {

    /**
     * Prints {@code prompt} and reads one line of visible input.
     */
    String readLine(String prompt);

    /**
     * Prints {@code prompt} and reads one password without echoing
     * it when a real console is available.
     */
    char[] readPassword(String prompt);
}

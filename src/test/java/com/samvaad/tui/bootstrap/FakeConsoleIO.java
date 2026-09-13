package com.samvaad.tui.bootstrap;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Test double for {@link ConsoleIO} with queued line/password answers.
 */
final class FakeConsoleIO implements ConsoleIO {

    private final Deque<String> lines = new ArrayDeque<>();
    private char[] password = new char[0];
    private String lastPasswordPrompt;

    void addLine(String line) {
        lines.add(line);
    }

    void setPassword(char[] password) {
        // Stores the reference (no copy) so tests can assert that
        // callers clear it after use.
        this.password = password;
    }

    String lastPasswordPrompt() {
        return lastPasswordPrompt;
    }

    @Override
    public String readLine(String prompt) {
        if (lines.isEmpty()) {
            throw new IllegalStateException("No queued line for prompt: " + prompt);
        }
        return lines.removeFirst();
    }

    @Override
    public char[] readPassword(String prompt) {
        lastPasswordPrompt = prompt;
        // Deliberately returns the live array so tests can assert
        // that callers clear it after use.
        return password;
    }
}

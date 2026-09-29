package com.samvaad.tui.bootstrap;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Test double for {@link ConsoleIO} with queued line/password answers.
 */
final class FakeConsoleIO implements ConsoleIO {

    private final Deque<String> lines = new ArrayDeque<>();
    private final Deque<char[]> queuedPasswords = new ArrayDeque<>();
    private char[] password = new char[0];
    private String lastPasswordPrompt;
    private int passwordReads;

    void addLine(String line) {
        lines.add(line);
    }

    /**
     * Queues one hidden-input answer, consumed before the single
     * {@link #setPassword} value. Lets tests give the vault prompt and a
     * later recovery-code prompt different answers.
     */
    void addPassword(char[] password) {
        queuedPasswords.add(password);
    }

    int passwordReads() {
        return passwordReads;
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
        passwordReads++;
        if (!queuedPasswords.isEmpty()) {
            return queuedPasswords.removeFirst();
        }
        // Deliberately returns the live array so tests can assert
        // that callers clear it after use.
        return password;
    }
}

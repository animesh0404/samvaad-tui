package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConsolePrompterTest {

    @Test
    void returnsProvidedServerUrlTrimmed() {
        ConsolePrompter prompter = new ConsolePrompter(new FakeConsoleIO());
        assertEquals("http://localhost:8080", prompter.promptServerUrl("  http://localhost:8080  "));
    }

    @Test
    void repromptsOnBlankServerUrl() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.addLine("");
        io.addLine("   ");
        io.addLine("http://localhost:8080");
        ConsolePrompter prompter = new ConsolePrompter(io);
        assertEquals("http://localhost:8080", prompter.promptServerUrl(null));
    }

    @Test
    void repromptsOnBlankUsername() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.addLine("");
        io.addLine("alice");
        ConsolePrompter prompter = new ConsolePrompter(io);
        assertEquals("alice", prompter.promptUsername(null));
    }

    @Test
    void delegatesPasswordPrompt() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        ConsolePrompter prompter = new ConsolePrompter(io);
        assertArrayEquals("s3cret".toCharArray(), prompter.promptPassword());
        assertEquals("Password: ", io.lastPasswordPrompt());
    }
}

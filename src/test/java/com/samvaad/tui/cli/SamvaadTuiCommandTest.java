package com.samvaad.tui.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.samvaad.tui.bootstrap.AppBootstrap;
import com.samvaad.tui.bootstrap.ConsoleIO;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class SamvaadTuiCommandTest {

    private static SamvaadTuiCommand parse(String... args) {
        SamvaadTuiCommand command = new SamvaadTuiCommand(new AppBootstrap(new NoopConsoleIO()));
        new CommandLine(command).parseArgs(args);
        return command;
    }

    @Test
    void parsesServerAndUsername() {
        SamvaadTuiCommand command = parse("--server", "http://localhost:8080", "--username", "alice");
        assertEquals("http://localhost:8080", command.getServerUrl());
        assertEquals("alice", command.getUsername());
    }

    @Test
    void missingOptionsStayNullForPrompting() {
        SamvaadTuiCommand command = parse();
        assertNull(command.getServerUrl());
        assertNull(command.getUsername());
    }

    private static final class NoopConsoleIO implements ConsoleIO {
        @Override
        public String readLine(String prompt) {
            return "";
        }

        @Override
        public char[] readPassword(String prompt) {
            return new char[0];
        }
    }
}

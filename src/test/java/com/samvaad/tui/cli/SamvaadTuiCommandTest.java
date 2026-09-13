package com.samvaad.tui.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.HttpResult;
import com.samvaad.tui.api.HttpTransport;
import com.samvaad.tui.bootstrap.AppBootstrap;
import com.samvaad.tui.bootstrap.ConsoleIO;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class SamvaadTuiCommandTest {

    private static SamvaadTuiCommand parse(String... args) {
        SamvaadTuiCommand command = new SamvaadTuiCommand(
                new AppBootstrap(new NoopConsoleIO(), new AuthApiClient(new UnusedTransport())));
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

    private static final class UnusedTransport implements HttpTransport {
        @Override
        public HttpResult post(String baseUrl, String path, String jsonBody, String bearerToken) {
            throw new UnsupportedOperationException("no HTTP in CLI parsing tests");
        }
    }
}

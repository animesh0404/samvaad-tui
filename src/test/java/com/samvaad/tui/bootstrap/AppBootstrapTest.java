package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.FakeHttpTransport;
import com.samvaad.tui.cli.CliOptions;
import com.samvaad.tui.ui.TuiException;
import com.samvaad.tui.ui.TuiLauncher;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AppBootstrapTest {

    private static final String AUTH_JSON =
            "{\"accessToken\":\"access-1\",\"refreshToken\":\"refresh-1\",\"expiresIn\":3600,\"sessionId\":\"sid-1\"}";

    @Test
    void loginLogoutFlowReturnsZero() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(200, "");

        int exit = new AppBootstrap(io, new AuthApiClient(transport), (username, serverUrl) -> { }).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(0, exit);
        assertEquals(2, transport.calls().size());
        assertEquals("/api/auth/login", transport.calls().get(0).path());
        assertEquals("/api/auth/logout", transport.calls().get(1).path());
        assertEquals("access-1", transport.calls().get(1).bearerToken());
    }

    @Test
    void promptsForMissingValues() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.addLine("http://localhost:8080");
        io.addLine("alice");
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(200, "");

        int exit = new AppBootstrap(io, new AuthApiClient(transport), (username, serverUrl) -> { }).run(new CliOptions(null, null));

        assertEquals(0, exit);
    }

    @Test
    void returnsTwoOnInvalidServerUrl() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();

        int exit = new AppBootstrap(io, new AuthApiClient(transport), (username, serverUrl) -> { }).run(
                new CliOptions("localhost:8080", "alice"));

        assertEquals(2, exit);
        assertTrue(transport.calls().isEmpty(), "no HTTP call must happen before valid config");
    }

    @Test
    void returnsOneOnAuthenticationFailure() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("wrong".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"error\":\"unauthorized\"}");

        int exit = new AppBootstrap(io, new AuthApiClient(transport), (username, serverUrl) -> { }).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(1, exit);
        assertEquals(1, transport.calls().size(), "logout must not run after failed login");
    }

    @Test
    void returnsTwoWhenInputEnds() {
        FakeHttpTransport transport = new FakeHttpTransport();

        int exit = new AppBootstrap(new FakeConsoleIO(), new AuthApiClient(transport), (username, serverUrl) -> { })
                .run(new CliOptions(null, null));

        assertEquals(2, exit);
    }

    @Test
    void clearsPasswordAfterLogin() {
        FakeConsoleIO io = new FakeConsoleIO();
        char[] password = "s3cret".toCharArray();
        io.setPassword(password);
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(200, "");

        new AppBootstrap(io, new AuthApiClient(transport), (username, serverUrl) -> { }).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertArrayEquals(new char[]{'\0', '\0', '\0', '\0', '\0', '\0'}, password);
    }

    @Test
    void logoutFailureStillExitsZero() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(500, "boom");

        int exit = new AppBootstrap(io, new AuthApiClient(transport), (username, serverUrl) -> { }).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(0, exit);
    }

    @Test
    void neverPrintsSecrets() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("SENTINEL-PW".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200,
                "{\"accessToken\":\"SENTINEL-ACCESS\",\"refreshToken\":\"SENTINEL-REFRESH\","
                        + "\"expiresIn\":3600,\"sessionId\":\"sid-1\"}");
        transport.addJson(200, "");

        String output = runCaptured(
                () -> new AppBootstrap(io, new AuthApiClient(transport), (username, serverUrl) -> { }).run(
                        new CliOptions("http://localhost:8080", "alice")));

        assertFalse(output.contains("SENTINEL-PW"), "password must never be printed");
        assertFalse(output.contains("SENTINEL-ACCESS"), "access token must never be printed");
        assertFalse(output.contains("SENTINEL-REFRESH"), "refresh token must never be printed");
        assertTrue(output.contains("alice"), "username summary must be printed");
        assertTrue(output.contains("sid-1"), "session id summary must be printed");
    }

    @Test
    void launchesTuiWithDisplayDataBetweenLoginAndLogout() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(200, "");
        RecordingTui tui = new RecordingTui();

        int exit = new AppBootstrap(io, new AuthApiClient(transport), tui).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(0, exit);
        assertEquals("alice", tui.username);
        assertEquals("http://localhost:8080", tui.serverUrl);
        assertEquals(2, transport.calls().size());
        assertEquals("/api/auth/login", transport.calls().get(0).path());
        assertEquals("/api/auth/logout", transport.calls().get(1).path());
    }

    @Test
    void tuiFailureStillLogsOutAndReturnsOne() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(200, "");

        int exit = new AppBootstrap(io, new AuthApiClient(transport),
                (username, serverUrl) -> {
                    throw new TuiException("No terminal.");
                }).run(new CliOptions("http://localhost:8080", "alice"));

        assertEquals(1, exit);
        assertEquals(2, transport.calls().size(), "logout must still revoke the server session");
        assertEquals("/api/auth/logout", transport.calls().get(1).path());
    }

    private static final class RecordingTui implements TuiLauncher {
        private String username;
        private String serverUrl;

        @Override
        public void launch(String username, String serverUrl) {
            this.username = username;
            this.serverUrl = serverUrl;
        }
    }

    private static String runCaptured(Runnable runnable) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);
            System.setErr(stream);
            runnable.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}

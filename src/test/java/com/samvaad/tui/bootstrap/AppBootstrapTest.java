package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.ConversationApiClient;
import com.samvaad.tui.api.FakeHttpTransport;
import com.samvaad.tui.auth.TestTokens;
import com.samvaad.tui.cli.CliOptions;
import com.samvaad.tui.ui.TuiException;
import com.samvaad.tui.ui.TuiLauncher;
import com.samvaad.tui.ui.TuiSession;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppBootstrapTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ACCESS = TestTokens.accessTokenFor(USER_ID);
    private static final String AUTH_JSON = "{\"accessToken\":\"" + ACCESS + "\","
            + "\"refreshToken\":\"refresh-1\",\"expiresIn\":3600,\"sessionId\":\"sid-1\"}";
    private static final String LIST_JSON = "[{\"conversationId\":"
            + "\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
            + "\"otherParticipantUserId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\","
            + "\"otherParticipantUsername\":\"bob\","
            + "\"lastSequenceNumber\":3,"
            + "\"updatedAt\":\"2026-09-14T10:15:30\"}]";

    private static AppBootstrap bootstrap(
            FakeConsoleIO io, FakeHttpTransport transport, TuiLauncher tui) {
        return new AppBootstrap(io, new AuthApiClient(transport),
                new ConversationApiClient(transport), tui);
    }

    private static void queueLoginListLogout(FakeHttpTransport transport) {
        transport.addJson(200, AUTH_JSON);
        transport.addJson(200, LIST_JSON);
        transport.addJson(200, "");
    }

    @Test
    void loginListAndLogoutFlowReturnsZero() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        queueLoginListLogout(transport);

        int exit = bootstrap(io, transport, (session) -> { }).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(0, exit);
        assertEquals(3, transport.calls().size());
        assertEquals("/api/auth/login", transport.calls().get(0).path());
        assertEquals("/api/conversations/direct?limit=20&offset=0", transport.calls().get(1).path());
        assertEquals("/api/auth/logout", transport.calls().get(2).path());
    }

    @Test
    void launchesTuiWithServerBackedState() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        queueLoginListLogout(transport);
        RecordingTui tui = new RecordingTui();

        int exit = bootstrap(io, transport, tui).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(0, exit);
        assertEquals("alice", tui.session.username());
        assertEquals("http://localhost:8080", tui.session.serverUrl());
        assertEquals(USER_ID, tui.session.store().currentUserId());
        assertEquals(1, tui.session.store().conversations().size());
        assertEquals("bob", tui.session.store().conversations().get(0).displayName());
        assertEquals(3, tui.session.store().conversations().get(0).lastSequenceNumber());
    }

    @Test
    void emptyListStillLaunchesTui() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(200, "[]");
        transport.addJson(200, "");
        RecordingTui tui = new RecordingTui();

        int exit = bootstrap(io, transport, tui).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(0, exit);
        assertTrue(tui.session.store().conversations().isEmpty());
        assertEquals("/api/auth/logout", transport.calls().get(2).path());
    }

    @Test
    void promptsForMissingValues() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.addLine("http://localhost:8080");
        io.addLine("alice");
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        queueLoginListLogout(transport);

        int exit = bootstrap(io, transport, (session) -> { }).run(new CliOptions(null, null));

        assertEquals(0, exit);
    }

    @Test
    void returnsTwoOnInvalidServerUrl() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();

        int exit = bootstrap(io, transport, (session) -> { }).run(
                new CliOptions("localhost:8080", "alice"));

        assertEquals(2, exit);
        assertTrue(transport.calls().isEmpty(), "no HTTP call must happen before valid config");
    }

    @Test
    void returnsOneOnAuthenticationFailure() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("wrong".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(401, "{\"message\":\"unauthorized\"}");

        int exit = bootstrap(io, transport, (session) -> { }).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(1, exit);
        assertEquals(1, transport.calls().size(), "nothing must run after failed login");
    }

    @Test
    void returnsOneWhenConversationListFails() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(500, "boom");
        transport.addJson(200, "");
        RecordingTui tui = new RecordingTui();

        int exit = bootstrap(io, transport, tui).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(1, exit);
        assertTrue(tui.session == null, "TUI must not launch without conversations");
        assertEquals(3, transport.calls().size(), "server session must still be revoked");
        assertEquals("/api/auth/logout", transport.calls().get(2).path());
    }

    @Test
    void returnsTwoWhenInputEnds() {
        FakeHttpTransport transport = new FakeHttpTransport();

        int exit = bootstrap(new FakeConsoleIO(), transport, (session) -> { })
                .run(new CliOptions(null, null));

        assertEquals(2, exit);
    }

    @Test
    void clearsPasswordAfterLogin() {
        FakeConsoleIO io = new FakeConsoleIO();
        char[] password = "s3cret".toCharArray();
        io.setPassword(password);
        FakeHttpTransport transport = new FakeHttpTransport();
        queueLoginListLogout(transport);

        bootstrap(io, transport, (session) -> { }).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertArrayEquals(new char[]{'\0', '\0', '\0', '\0', '\0', '\0'}, password);
    }

    @Test
    void logoutFailureStillExitsZero() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, AUTH_JSON);
        transport.addJson(200, LIST_JSON);
        transport.addJson(500, "boom");

        int exit = bootstrap(io, transport, (session) -> { }).run(
                new CliOptions("http://localhost:8080", "alice"));

        assertEquals(0, exit);
    }

    @Test
    void tuiFailureStillLogsOutAndReturnsOne() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("s3cret".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        queueLoginListLogout(transport);

        int exit = bootstrap(io, transport, (session) -> {
            throw new TuiException("No terminal.");
        }).run(new CliOptions("http://localhost:8080", "alice"));

        assertEquals(1, exit);
        assertEquals(3, transport.calls().size(), "logout must still revoke the server session");
        assertEquals("/api/auth/logout", transport.calls().get(2).path());
    }

    @Test
    void neverPrintsSecrets() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.setPassword("SENTINEL-PW".toCharArray());
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.addJson(200, "{\"accessToken\":\"" + ACCESS + "\","
                + "\"refreshToken\":\"SENTINEL-REFRESH\","
                + "\"expiresIn\":3600,\"sessionId\":\"sid-1\"}");
        transport.addJson(200, LIST_JSON);
        transport.addJson(200, "");

        String output = runCaptured(() -> bootstrap(io, transport, (session) -> { }).run(
                new CliOptions("http://localhost:8080", "alice")));

        assertFalse(output.contains("SENTINEL-PW"), "password must never be printed");
        assertFalse(output.contains(ACCESS), "access token must never be printed");
        assertFalse(output.contains("SENTINEL-REFRESH"), "refresh token must never be printed");
        assertTrue(output.contains("alice"), "username summary must be printed");
        assertTrue(output.contains("sid-1"), "session id summary must be printed");
    }

    private static final class RecordingTui implements TuiLauncher {
        private TuiSession session;

        @Override
        public void launch(TuiSession session) {
            this.session = session;
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

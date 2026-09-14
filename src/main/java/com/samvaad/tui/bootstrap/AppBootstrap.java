package com.samvaad.tui.bootstrap;

import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.ConversationApiClient;
import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.api.dto.AuthResponse;
import com.samvaad.tui.api.dto.ConversationResponse;
import com.samvaad.tui.api.dto.MessageResponse;
import com.samvaad.tui.auth.Credentials;
import com.samvaad.tui.cli.CliOptions;
import com.samvaad.tui.config.AppConfig;
import com.samvaad.tui.config.AppConfigResolver;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import com.samvaad.tui.realtime.RealtimeClient;
import com.samvaad.tui.realtime.RealtimeException;
import com.samvaad.tui.realtime.RealtimeManager;
import com.samvaad.tui.session.AuthSession;
import com.samvaad.tui.session.SessionState;
import com.samvaad.tui.ui.MessageHistoryLoader;
import com.samvaad.tui.ui.TuiException;
import com.samvaad.tui.ui.TuiLauncher;
import com.samvaad.tui.ui.TuiSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 4 startup flow: resolve config, log in, load the server
 * conversation list, enter the fullscreen TUI with server-backed state,
 * then revoke the server session via logout and clear all local secrets.
 */
public final class AppBootstrap {

    private final ConsoleIO io;
    private final AuthApiClient authApi;
    private final ConversationApiClient conversationsApi;
    private final RealtimeClient realtimeClient;
    private final TuiLauncher tui;

    public AppBootstrap(ConsoleIO io, AuthApiClient authApi, ConversationApiClient conversationsApi,
            RealtimeClient realtimeClient, TuiLauncher tui) {
        this.io = Objects.requireNonNull(io, "io");
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.conversationsApi = Objects.requireNonNull(conversationsApi, "conversationsApi");
        this.realtimeClient = Objects.requireNonNull(realtimeClient, "realtimeClient");
        this.tui = Objects.requireNonNull(tui, "tui");
    }

    public int run(CliOptions options) {
        Objects.requireNonNull(options, "options");
        ConsolePrompter prompter = new ConsolePrompter(io);
        AppConfig config;
        try {
            String serverUrl = prompter.promptServerUrl(options.serverUrl());
            String username = prompter.promptUsername(options.username());
            config = AppConfigResolver.resolve(serverUrl, username);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        char[] password = prompter.promptPassword();
        Credentials credentials = new Credentials(config.username(), password);
        AuthSession auth;
        try {
            AuthResponse response =
                    authApi.login(config.serverUrl(), credentials.username(), credentials.password());
            auth = AuthSession.from(response);
        } catch (SamvaadApiException | IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } finally {
            credentials.clear();
            Arrays.fill(password, '\0');
        }

        SessionState session = SessionState.authenticated(config, auth);
        System.out.println("Server: " + session.config().serverUrl());
        System.out.println("Username: " + session.config().username());
        System.out.println("Session: " + auth.sessionId());
        System.out.println("Authenticated: yes (expires in " + auth.expiresInSeconds() + " seconds)");

        List<ConversationEntry> entries;
        try {
            entries = loadConversations(config.serverUrl(), auth.accessToken());
        } catch (SamvaadApiException | IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            revokeQuietly(config.serverUrl(), auth.accessToken());
            session = session.cleared();
            return 1;
        }
        ConversationStore store = new ConversationStore(auth.userId(), entries);
        MessageHistoryLoader history = (conversationId, afterSequence, limit) -> loadHistory(
                config.serverUrl(), auth.accessToken(), conversationId, afterSequence, limit);
        RealtimeManager realtime =
                new RealtimeManager(realtimeClient, config.serverUrl(), auth.accessToken(), store, history);
        try {
            realtime.connect();
        } catch (RealtimeException e) {
            System.err.println("Warning: realtime unavailable (" + e.getMessage() + "). History only.");
        }
        TuiSession tuiSession =
                new TuiSession(config.username(), config.serverUrl(), store, history, realtime);
        int tuiExit = 0;
        try {
            tui.launch(tuiSession);
        } catch (TuiException e) {
            System.err.println("Error: " + e.getMessage());
            tuiExit = 1;
        } finally {
            realtime.disconnect();
        }
        try {
            authApi.logout(config.serverUrl(), auth.accessToken());
            System.out.println("Logged out. Server session revoked.");
        } catch (SamvaadApiException e) {
            System.err.println("Warning: logout failed (" + e.getMessage() + "). Local session cleared.");
        } finally {
            session = session.cleared();
        }
        return tuiExit;
    }

    private List<ConversationEntry> loadConversations(String serverUrl, String accessToken) {
        List<ConversationResponse> responses =
                conversationsApi.listDirectConversations(serverUrl, accessToken);
        List<ConversationEntry> entries = new ArrayList<>(responses.size());
        for (ConversationResponse response : responses) {
            entries.add(new ConversationEntry(
                    response.conversationId(),
                    response.otherParticipantUserId(),
                    response.otherParticipantUsername(),
                    response.lastSequenceNumber(),
                    response.updatedAt()));
        }
        return entries;
    }

    private List<MessageEntry> loadHistory(String serverUrl, String accessToken,
            UUID conversationId, long afterSequence, int limit) {
        List<MessageResponse> responses = conversationsApi.getMessageHistory(
                serverUrl, accessToken, conversationId, afterSequence, limit);
        List<MessageEntry> entries = new ArrayList<>(responses.size());
        for (MessageResponse response : responses) {
            entries.add(new MessageEntry(
                    response.messageId(),
                    response.senderUserId(),
                    response.sequenceNumber(),
                    response.content(),
                    response.serverTimestamp(),
                    response.requestId()));
        }
        return entries;
    }

    private void revokeQuietly(String serverUrl, String accessToken) {
        try {
            authApi.logout(serverUrl, accessToken);
        } catch (SamvaadApiException ignored) {
            // Best effort: the session may already be unusable.
        }
    }
}

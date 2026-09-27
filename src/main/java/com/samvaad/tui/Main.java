package com.samvaad.tui;

import com.samvaad.tui.api.AuthApiClient;
import com.samvaad.tui.api.ConversationApiClient;
import com.samvaad.tui.api.E2eeDeviceApiClient;
import com.samvaad.tui.api.E2eeMessageApiClient;
import com.samvaad.tui.api.FriendRequestApiClient;
import com.samvaad.tui.api.FriendsApiClient;
import com.samvaad.tui.api.JdkHttpTransport;
import com.samvaad.tui.api.UserLookupApiClient;
import com.samvaad.tui.bootstrap.AppBootstrap;
import com.samvaad.tui.bootstrap.E2eePaths;
import com.samvaad.tui.bootstrap.E2eeStartupInitializer;
import com.samvaad.tui.bootstrap.SystemConsoleIO;
import com.samvaad.tui.cli.SamvaadTuiCommand;
import com.samvaad.tui.realtime.SpringRealtimeClient;
import com.samvaad.tui.ui.TuiApp;
import picocli.CommandLine;

/**
 * Application entry point for the Samvaad TUI client.
 *
 * <p>Phase 4: CLI bootstrap, server authentication, server-backed
 * conversations and message history in the fullscreen TUI.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        JdkHttpTransport transport = new JdkHttpTransport();
        SystemConsoleIO console = new SystemConsoleIO();
        AppBootstrap bootstrap = new AppBootstrap(
                console,
                new AuthApiClient(transport),
                new ConversationApiClient(transport),
                new UserLookupApiClient(transport),
                new FriendRequestApiClient(transport),
                new FriendsApiClient(transport),
                new E2eeStartupInitializer(console,
                        new E2eeDeviceApiClient(transport),
                        new E2eeMessageApiClient(transport),
                        E2eePaths.defaultE2eeDir()),
                new SpringRealtimeClient(),
                new TuiApp());
        SamvaadTuiCommand command = new SamvaadTuiCommand(bootstrap);
        int exitCode = new CommandLine(command).execute(args);
        System.exit(exitCode);
    }
}

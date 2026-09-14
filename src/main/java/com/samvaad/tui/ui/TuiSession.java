package com.samvaad.tui.ui;

import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.realtime.RealtimeManager;

/**
 * Token-free session view handed to the fullscreen UI: display identity,
 * server-backed conversation state, the history loader, realtime
 * coordination, and friend-request state with its token-free service
 * seam. Carries no credentials or tokens.
 */
public record TuiSession(
        String username,
        String serverUrl,
        ConversationStore store,
        MessageHistoryLoader historyLoader,
        RealtimeManager realtime,
        FriendRequestStore friendStore,
        FriendService friends) {
}

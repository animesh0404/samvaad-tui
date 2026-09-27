package com.samvaad.tui.ui;

import com.samvaad.tui.bootstrap.E2eeMessageSender;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.model.FriendStore;
import com.samvaad.tui.realtime.RealtimeManager;

/**
 * Token-free session view handed to the fullscreen UI: display identity,
 * server-backed conversation state, the history loader, realtime
 * coordination, friend-request state with its token-free service seam,
 * the authoritative friends list, and the conversation-list loader.
 * Carries no credentials or tokens.
 *
 * <p>The optional E2EE sender (null when E2EE was unavailable this
 * session) offers encrypted direct messaging alongside — never instead
 * of — the plaintext realtime path.
 */
public record TuiSession(
        String username,
        String serverUrl,
        ConversationStore store,
        MessageHistoryLoader historyLoader,
        RealtimeManager realtime,
        FriendRequestStore friendStore,
        FriendService friends,
        FriendStore friendList,
        ConversationListLoader conversationLoader,
        E2eeMessageSender e2eeSender) {
}

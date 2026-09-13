package com.samvaad.tui.ui;

import com.samvaad.tui.model.ConversationStore;

/**
 * Token-free session view handed to the fullscreen UI: display identity,
 * server-backed conversation state, and the history loader. Carries no
 * credentials or tokens.
 */
public record TuiSession(
        String username,
        String serverUrl,
        ConversationStore store,
        MessageHistoryLoader historyLoader) {
}

package com.samvaad.tui.ui;

import java.util.List;
import java.util.Map;

/**
 * In-memory preview data so the Phase 3 shell can be navigated before
 * server conversation APIs are integrated. Replaced by real server data
 * in Phase 4; must never be mistaken for server state.
 */
public final class PreviewInbox {

    private static final List<ConversationView> CONVERSATIONS = List.of(
            new ConversationView("preview-alice", "Alice"),
            new ConversationView("preview-bob", "Bob"),
            new ConversationView("preview-charlie", "Charlie"));

    private static final Map<String, List<ChatMessageView>> MESSAGES = Map.of(
            "preview-alice", List.of(
                    new ChatMessageView("Alice", "Hello", false),
                    new ChatMessageView("You", "Hi!", true),
                    new ChatMessageView("Alice", "How are you?", false)),
            "preview-bob", List.of(
                    new ChatMessageView("Bob", "Did you see the release notes?", false),
                    new ChatMessageView("You", "Not yet, sending them over?", true)),
            "preview-charlie", List.of(
                    new ChatMessageView("Charlie", "Lunch tomorrow?", false)));

    private PreviewInbox() {
    }

    public static List<ConversationView> conversations() {
        return CONVERSATIONS;
    }

    public static List<ChatMessageView> messagesFor(String conversationId) {
        return MESSAGES.getOrDefault(conversationId, List.of());
    }
}

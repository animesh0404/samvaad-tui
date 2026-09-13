package com.samvaad.tui.ui;

/**
 * UI-side view of one conversation entry. Carries only display data;
 * server DTOs stay in {@code api.dto}.
 *
 * @param id stable conversation identifier for selection state
 * @param title display title shown in the sidebar
 */
public record ConversationView(String id, String title) {
}

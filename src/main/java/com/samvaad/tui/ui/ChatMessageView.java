package com.samvaad.tui.ui;

/**
 * UI-side view of one chat line.
 *
 * @param sender display name of the sender
 * @param text message text
 * @param mine true when the message is shown as sent by the local user
 */
public record ChatMessageView(String sender, String text, boolean mine) {
}

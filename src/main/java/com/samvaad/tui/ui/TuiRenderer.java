package com.samvaad.tui.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.MessageEntry;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Draws the whole TUI shell on every input event: header, conversation
 * sidebar, chat panel, composer, status line, and the help overlay.
 *
 * <p>Renders server-backed state exactly as held: conversation order and
 * message order are never re-sorted here.
 */
public final class TuiRenderer {

    static final int MIN_COLUMNS = 50;
    static final int MIN_ROWS = 12;

    private static final String STATUS_HINTS =
            "Up/Down select - Tab focus - Enter open - ? help - F10 quit";

    static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public void render(Screen screen, TuiState state, ConversationStore store,
            String username, String serverUrl) {
        TerminalSize size = screen.getTerminalSize();
        int cols = size.getColumns();
        int rows = size.getRows();
        TextGraphics tg = screen.newTextGraphics();
        if (cols < MIN_COLUMNS || rows < MIN_ROWS) {
            tg.fill(' ');
            tg.putString(0, 0, "Terminal too small - need at least "
                    + MIN_COLUMNS + "x" + MIN_ROWS + ".");
            return;
        }
        drawHeader(tg, cols, username, serverUrl);
        int panelTop = 1;
        int panelBottom = rows - 3;
        int sideWidth = Math.max(20, Math.min(30, cols / 3));
        List<ConversationEntry> conversations = store.conversations();
        boolean listFocused = state.focus() == TuiState.Focus.CONVERSATIONS;
        drawSidebar(tg, state, conversations, sideWidth, panelTop, panelBottom, listFocused);
        drawChat(tg, state, store, conversations, sideWidth, cols, panelTop, panelBottom,
                !listFocused);
        drawComposer(tg, screen, state, cols, rows - 2);
        drawStatus(tg, state, cols, rows - 1);
        if (state.helpVisible()) {
            drawHelp(tg, cols, rows);
        }
    }

    private void drawHeader(TextGraphics tg, int cols, String username, String serverUrl) {
        String left = " Samvaad";
        String right = username + " @ " + serverUrl + " ";
        int gap = cols - left.length() - right.length();
        String line;
        if (gap < 1) {
            line = truncate(left.trim() + " " + right.trim(), cols);
        } else {
            line = left + " ".repeat(gap) + right;
        }
        tg.putString(0, 0, padRight(line, cols), SGR.REVERSE);
    }

    private void drawSidebar(TextGraphics tg, TuiState state,
            List<ConversationEntry> conversations, int width, int top, int bottom,
            boolean focused) {
        drawBox(tg, 0, top, width, bottom - top + 1, " Conversations ", focused);
        if (conversations.isEmpty()) {
            tg.putString(1, top + 1, truncate("  (no conversations)", width - 2));
            return;
        }
        for (int i = 0; i < conversations.size(); i++) {
            int row = top + 1 + i;
            if (row >= bottom) {
                break;
            }
            boolean selected = i == state.selectedIndex();
            String entry = (selected ? "> " : "  ") + conversations.get(i).displayName();
            entry = truncate(entry, width - 2);
            if (selected) {
                tg.putString(1, row, padRight(entry, width - 2), SGR.REVERSE);
            } else {
                tg.putString(1, row, padRight(entry, width - 2));
            }
        }
    }

    private void drawChat(TextGraphics tg, TuiState state, ConversationStore store,
            List<ConversationEntry> conversations, int left, int cols, int top, int bottom,
            boolean focused) {
        int width = cols - left;
        if (conversations.isEmpty()) {
            drawBox(tg, left, top, width, bottom - top + 1, " Conversation ", focused);
            tg.putString(left + 1, top + 1, truncate("No conversations yet.", width - 2));
            return;
        }
        int index = Math.min(state.selectedIndex(), conversations.size() - 1);
        ConversationEntry conversation = conversations.get(index);
        drawBox(tg, left, top, width, bottom - top + 1, " " + conversation.displayName() + " ",
                focused);
        switch (store.statusOf(conversation.conversationId())) {
            case NOT_LOADED, LOADING ->
                tg.putString(left + 1, top + 1, truncate("Loading history...", width - 2));
            case ERROR -> {
                String error = store.errorOf(conversation.conversationId());
                tg.putString(left + 1, top + 1,
                        truncate(error != null ? error : "Could not load history.", width - 2));
            }
            case LOADED -> {
                List<MessageEntry> messages = store.messagesOf(conversation.conversationId());
                if (messages.isEmpty()) {
                    tg.putString(left + 1, top + 1, truncate("No messages yet.", width - 2));
                    return;
                }
                int row = top + 1;
                for (MessageEntry message : messages) {
                    if (row >= bottom) {
                        break;
                    }
                    tg.putString(left + 1, row++,
                            truncate(senderLabel(store, conversation, message)
                                    + formatTimestamp(message.serverTimestamp()) + ": "
                                    + message.content(), width - 2));
                }
            }
        }
    }

    private static String senderLabel(
            ConversationStore store, ConversationEntry conversation, MessageEntry message) {
        if (message.senderUserId().equals(store.currentUserId())) {
            return "You";
        }
        return conversation.displayName();
    }

    /**
     * Compact rendering of the server-provided timestamp. Zone-less by
     * contract; displayed exactly as the server sent it.
     */
    static String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "";
        }
        return " [" + MESSAGE_TIME.format(timestamp) + "]";
    }

    private void drawComposer(TextGraphics tg, Screen screen, TuiState state, int cols, int row) {
        String prompt = "> ";
        tg.putString(0, row, prompt);
        int maxLen = cols - prompt.length() - 1;
        String buffer = state.composer();
        String visible = buffer.length() > maxLen ? buffer.substring(buffer.length() - maxLen) : buffer;
        tg.putString(prompt.length(), row, padRight(visible, maxLen));
        screen.setCursorPosition(new TerminalPosition(prompt.length() + visible.length(), row));
    }

    private void drawStatus(TextGraphics tg, TuiState state, int cols, int row) {
        String text = state.status().isEmpty() ? STATUS_HINTS : state.status();
        tg.putString(0, row, padRight(truncate(text, cols), cols), SGR.REVERSE);
    }

    private static final int HELP_HORIZONTAL_PADDING = 2;
    private static final int HELP_VERTICAL_PADDING = 1;

    private void drawHelp(TextGraphics tg, int cols, int rows) {
        List<String> lines = List.of(
                "Up/Down or k/j  Select conversation",
                "Tab             Move focus (list / composer)",
                "Enter           Open conversation / send message",
                "F1 or ?         Toggle this help",
                "Esc             Close help",
                "F10, Ctrl+C     Quit (q quits in the list)",
                "",
                "Message sending and friends arrive in",
                "later phases.");
        int contentWidth = lines.stream().mapToInt(String::length).max().orElse(0);
        int width = Math.min(cols - 2, contentWidth + HELP_HORIZONTAL_PADDING * 2 + 2);
        // Only pad vertically when the terminal fits the padded box;
        // on minimum-height terminals fall back to the compact layout.
        int verticalPadding = rows >= lines.size() + 4 ? HELP_VERTICAL_PADDING : 0;
        int height = lines.size() + 2 + verticalPadding * 2;
        int left = Math.max(0, (cols - width) / 2);
        int top = Math.max(0, (rows - height) / 2);
        drawBox(tg, left, top, width, height, " Help ", false);
        for (int i = 0; i < lines.size(); i++) {
            tg.putString(left + 1 + HELP_HORIZONTAL_PADDING, top + 1 + verticalPadding + i,
                    truncate(lines.get(i), width - 2 - HELP_HORIZONTAL_PADDING * 2));
        }
    }

    private void drawBox(TextGraphics tg, int left, int top, int width, int height, String title,
            boolean focused) {
        int right = left + width - 1;
        int bottom = top + height - 1;
        // Fill the interior first so every frame authoritatively repaints
        // every cell it owns. Without this, Lanterna's diff-based refresh
        // leaves stale characters where new content is shorter than the
        // previously painted content (closed overlays, other conversations).
        tg.fillRectangle(new TerminalPosition(left + 1, top + 1),
                new TerminalSize(width - 2, height - 2), ' ');
        tg.setCharacter(left, top, Symbols.SINGLE_LINE_TOP_LEFT_CORNER);
        tg.setCharacter(right, top, Symbols.SINGLE_LINE_TOP_RIGHT_CORNER);
        tg.setCharacter(left, bottom, Symbols.SINGLE_LINE_BOTTOM_LEFT_CORNER);
        tg.setCharacter(right, bottom, Symbols.SINGLE_LINE_BOTTOM_RIGHT_CORNER);
        tg.drawLine(left + 1, top, right - 1, top, Symbols.SINGLE_LINE_HORIZONTAL);
        tg.drawLine(left + 1, bottom, right - 1, bottom, Symbols.SINGLE_LINE_HORIZONTAL);
        tg.drawLine(left, top + 1, left, bottom - 1, Symbols.SINGLE_LINE_VERTICAL);
        tg.drawLine(right, top + 1, right, bottom - 1, Symbols.SINGLE_LINE_VERTICAL);
        if (title != null && !title.isEmpty() && title.length() < width - 2) {
            if (focused) {
                tg.putString(left + 1, top, title, SGR.BOLD);
            } else {
                tg.putString(left + 1, top, title);
            }
        }
    }

    static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max));
    }

    static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }
}

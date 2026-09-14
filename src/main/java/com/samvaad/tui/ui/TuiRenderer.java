package com.samvaad.tui.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.samvaad.tui.model.ConversationEntry;
import com.samvaad.tui.model.ConversationStore;
import com.samvaad.tui.model.FriendEntry;
import com.samvaad.tui.model.FriendRequestEntry;
import com.samvaad.tui.model.FriendRequestStore;
import com.samvaad.tui.model.FriendStore;
import com.samvaad.tui.model.MessageEntry;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

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
            "Up/Down select - Left/Right tabs - Tab focus - Enter open - / search - ? help - F10 quit";

    static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public void render(Screen screen, TuiState state, ConversationStore store,
            String username, String serverUrl) {
        render(screen, state, store, username, serverUrl, new FriendRequestStore());
    }

    public void render(Screen screen, TuiState state, ConversationStore store,
            String username, String serverUrl, FriendRequestStore friendStore) {
        render(screen, state, store, username, serverUrl, friendStore, new FriendStore());
    }

    public void render(Screen screen, TuiState state, ConversationStore store,
            String username, String serverUrl, FriendRequestStore friendStore,
            FriendStore friendList) {
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
        drawSidebar(tg, state, conversations, friendList.friends(), sideWidth, panelTop,
                panelBottom, listFocused);
        if (state.view() == TuiState.View.SEARCH) {
            drawSearch(tg, screen, state, friendStore, sideWidth, cols, panelTop, panelBottom);
        } else if (state.view() == TuiState.View.REQUESTS) {
            drawRequests(tg, state, friendStore, store.currentUserId(), sideWidth, cols,
                    panelTop, panelBottom);
        } else {
            drawChat(tg, state, store, conversations, sideWidth, cols, panelTop, panelBottom,
                    !listFocused);
        }
        if (state.view() == TuiState.View.CHAT) {
            drawComposer(tg, screen, state, cols, rows - 2);
        } else {
            drawModeLine(tg, screen, state, cols, rows - 2);
        }
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
            List<ConversationEntry> conversations, List<FriendEntry> friends, int width, int top,
            int bottom, boolean focused) {
        drawBox(tg, 0, top, width, bottom - top + 1, " Conversations ", focused);
        drawSidebarTabs(tg, state, width, top);
        int firstRow = top + 2;
        if (state.sidebarTab() == TuiState.SidebarTab.FRIENDS) {
            drawFriendList(tg, state, friends, width, firstRow, bottom);
            return;
        }
        if (conversations.isEmpty()) {
            tg.putString(1, firstRow, truncate("  (no conversations)", width - 2));
            return;
        }
        for (int i = 0; i < conversations.size(); i++) {
            int row = firstRow + i;
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

    private void drawSidebarTabs(TextGraphics tg, TuiState state, int width, int top) {
        boolean friendsActive = state.sidebarTab() == TuiState.SidebarTab.FRIENDS;
        int col = 1;
        col = putTabSegment(tg, "> CONVERSATIONS", !friendsActive, col, top + 1, width);
        col = putTabSegment(tg, " | ", false, col, top + 1, width);
        putTabSegment(tg, friendsActive ? "> FRIENDS" : "  FRIENDS", friendsActive, col, top + 1,
                width);
    }

    private static int putTabSegment(TextGraphics tg, String text, boolean active, int col,
            int row, int width) {
        int max = Math.max(0, width - 1 - col);
        String visible = truncate(text, max);
        if (visible.isEmpty()) {
            return col;
        }
        if (active) {
            tg.putString(col, row, visible, SGR.REVERSE);
        } else {
            tg.putString(col, row, visible);
        }
        return col + visible.length();
    }

    private void drawFriendList(TextGraphics tg, TuiState state, List<FriendEntry> friends,
            int width, int firstRow, int bottom) {
        if (friends.isEmpty()) {
            tg.putString(1, firstRow, truncate("  (no friends yet)", width - 2));
            return;
        }
        for (int i = 0; i < friends.size(); i++) {
            int row = firstRow + i;
            if (row >= bottom) {
                break;
            }
            boolean selected = i == state.friendSelectedIndex();
            String entry = (selected ? "> " : "  ") + friends.get(i).username();
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
        if (state.pendingNewChat() != null) {
            drawBox(tg, left, top, width, bottom - top + 1, " New chat ", focused);
            tg.putString(left + 1, top + 1,
                    truncate("New chat with " + state.pendingNewChat().username(), width - 2));
            if (top + 2 < bottom) {
                tg.putString(left + 1, top + 2,
                        truncate("Type the first message below, Enter to send.", width - 2));
            }
            return;
        }
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

    private void drawSearch(TextGraphics tg, Screen screen, TuiState state,
            FriendRequestStore friendStore, int left, int cols, int top, int bottom) {
        int width = cols - left;
        drawBox(tg, left, top, width, bottom - top + 1, " Search user ", true);
        int inner = width - 2;
        int row = top + 1;
        if (row >= bottom) {
            return;
        }
        boolean inputFocused = state.searchFocus() == TuiState.SearchFocus.INPUT;
        String prompt = inputFocused ? "> " : "  ";
        String input = prompt + "Exact username: " + state.searchInput();
        tg.putString(left + 1, row++, truncate(padRight(input, inner), inner),
                inputFocused ? SGR.REVERSE : SGR.BOLD);
        if (row >= bottom) {
            return;
        }
        switch (friendStore.lookupStatus()) {
            case NOT_LOADED ->
                tg.putString(left + 1, row++, truncate("Type a username, Enter to look up.", inner));
            case LOADING ->
                tg.putString(left + 1, row++, truncate("Looking up...", inner));
            case ERROR -> {
                String error = friendStore.lookupError();
                tg.putString(left + 1, row++,
                        truncate(error != null ? error : "User lookup failed.", inner));
            }
            case LOADED -> {
                if (friendStore.lookupResult() != null) {
                    tg.putString(left + 1, row++,
                            truncate("Found: " + friendStore.lookupResult().username(), inner));
                }
            }
        }
        if (row >= bottom) {
            return;
        }
        if (friendStore.lookupResult() != null
                && friendStore.lookupStatus() == FriendRequestStore.LoadStatus.LOADED) {
            boolean sendFocused = state.searchFocus() == TuiState.SearchFocus.SEND;
            String send = (sendFocused ? "> " : "  ") + "[Send friend request]";
            tg.putString(left + 1, row++, truncate(padRight(send, inner), inner),
                    sendFocused ? SGR.REVERSE : SGR.BOLD);
        }
        if (row >= bottom) {
            return;
        }
        tg.putString(left + 1, row, truncate("Enter lookup - Tab send - Esc back", inner));
        if (inputFocused) {
            screen.setCursorPosition(new TerminalPosition(
                    left + 1 + prompt.length() + "Exact username: ".length()
                            + state.searchInput().length(),
                    top + 1));
        }
    }

    private void drawRequests(TextGraphics tg, TuiState state, FriendRequestStore friendStore,
            UUID currentUserId, int left, int cols, int top, int bottom) {
        int width = cols - left;
        drawBox(tg, left, top, width, bottom - top + 1, " Friend requests ", true);
        int inner = width - 2;
        int row = top + 1;
        if (row >= bottom) {
            return;
        }
        List<FriendRequestEntry> incoming = friendStore.incoming();
        List<FriendRequestEntry> outgoing = friendStore.outgoing();
        boolean incomingActive = state.requestSection() == TuiState.RequestSection.INCOMING;
        String tabs = (incomingActive ? "> " : "  ") + "Incoming (" + incoming.size() + ")   "
                + (!incomingActive ? "> " : "  ") + "Outgoing (" + outgoing.size() + ")";
        tg.putString(left + 1, row++, truncate(padRight(tabs, inner), inner), SGR.BOLD);
        if (row >= bottom) {
            return;
        }
        if (incomingActive) {
            row = drawRequestList(tg, state, friendStore.incomingStatus(), friendStore.incomingError(),
                    incoming, currentUserId, true, left, row, bottom, inner);
            if (row < bottom) {
                tg.putString(left + 1, row,
                        truncate("a accept - x reject - Enter accept - g refresh - Esc back", inner));
            }
        } else {
            row = drawRequestList(tg, state, friendStore.outgoingStatus(), friendStore.outgoingError(),
                    outgoing, currentUserId, false, left, row, bottom, inner);
            if (row < bottom) {
                tg.putString(left + 1, row,
                        truncate("c cancel - Enter cancel - g refresh - Esc back", inner));
            }
        }
    }

    private int drawRequestList(TextGraphics tg, TuiState state, FriendRequestStore.LoadStatus status,
            String error, List<FriendRequestEntry> entries, UUID currentUserId,
            boolean isIncoming, int left, int row, int bottom, int inner) {
        switch (status) {
            case NOT_LOADED -> {
                if (row < bottom) {
                    tg.putString(left + 1, row++, truncate("Loading...", inner));
                }
                return row;
            }
            case LOADING -> {
                if (entries.isEmpty() && row < bottom) {
                    tg.putString(left + 1, row++, truncate("Loading...", inner));
                    return row;
                }
            }
            case ERROR -> {
                if (row < bottom) {
                    tg.putString(left + 1, row++,
                            truncate(error != null ? error : "Could not load requests.", inner));
                }
                return row;
            }
            case LOADED -> {
                if (entries.isEmpty() && row < bottom) {
                    tg.putString(left + 1, row++, truncate("(none)", inner));
                    return row;
                }
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            if (row >= bottom) {
                break;
            }
            FriendRequestEntry entry = entries.get(i);
            String name = isIncoming ? entry.senderUsername() : entry.recipientUsername();
            if (name == null) {
                name = "Unknown user";
            }
            boolean selected = i == state.requestSelectedIndex();
            String line = (selected ? "> " : "  ") + name;
            tg.putString(left + 1, row++, truncate(padRight(line, inner), inner),
                    selected ? SGR.REVERSE : SGR.BOLD);
        }
        return row;
    }

    private void drawModeLine(TextGraphics tg, Screen screen, TuiState state, int cols, int row) {
        String hint = state.view() == TuiState.View.SEARCH
                ? "Search: type username, Enter lookup, Tab send, Esc back"
                : "Requests: Tab section, a/x/c act, g refresh, Esc back";
        tg.putString(0, row, padRight(truncate(hint, cols), cols));
        screen.setCursorPosition(null);
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
                "Left/Right      Switch Conversations/Friends tabs",
                "Tab             Move focus (list / composer)",
                "Enter           Open conversation / send message",
                "/               Search user by exact username",
                "r               Friend requests (incoming/outgoing)",
                "F1 or ?         Toggle this help",
                "Esc             Close help",
                "F10, Ctrl+C     Quit (q quits in the list)",
                "",
                "In search: type, Enter lookup, Tab to Send,",
                "Enter sends the friend request, Esc back.",
                "In requests: Tab section, Up/Down select,",
                "a accept, x reject, c cancel, Enter primary,",
                "g refresh, Esc or q back.",
                "In friends: Left/Right tabs, Up/Down select,",
                "Enter open chat, g refresh, Esc back.");
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

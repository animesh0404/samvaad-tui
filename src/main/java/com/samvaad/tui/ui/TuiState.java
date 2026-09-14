package com.samvaad.tui.ui;

import java.util.UUID;

/**
 * Mutable UI state for the TUI shell: selection, focus, composer buffer,
 * pending send, help overlay visibility, the transient status line, and
 * the user-search / friend-request workflows.
 */
public final class TuiState {

    public enum Focus {
        CONVERSATIONS,
        COMPOSER
    }

    /**
     * Top-level terminal view. CHAT is the Phase 5 conversation shell;
     * SEARCH hosts exact-username lookup; REQUESTS hosts the pending
     * incoming/outgoing friend-request lists.
     */
    public enum View {
        CHAT,
        SEARCH,
        REQUESTS
    }

    /**
     * Mini-focus inside the SEARCH view: the username input or the
     * send-request action for a successful lookup.
     */
    public enum SearchFocus {
        INPUT,
        SEND
    }

    /**
     * Which pending list is active inside the REQUESTS view.
     */
    public enum RequestSection {
        INCOMING,
        OUTGOING
    }

    /**
     * Active tab of the left sidebar in the CHAT view.
     */
    public enum SidebarTab {
        CONVERSATIONS,
        FRIENDS
    }

    /**
     * A friend selected for starting a new chat. Holds only the
     * friend's authoritative identity from the friends list; it never
     * represents a server conversation.
     */
    public record PendingChat(UUID userId, String username) {
    }

    static final int MAX_COMPOSER_LENGTH = 200;
    static final int MAX_SEARCH_LENGTH = 32;

    private Focus focus = Focus.CONVERSATIONS;
    private int selectedIndex;
    private String composer = "";
    private UUID pendingSend;
    private boolean helpVisible;
    private String status = "";
    private View view = View.CHAT;
    private SearchFocus searchFocus = SearchFocus.INPUT;
    private String searchInput = "";
    private RequestSection requestSection = RequestSection.INCOMING;
    private int requestSelectedIndex;
    private SidebarTab sidebarTab = SidebarTab.CONVERSATIONS;
    private int friendSelectedIndex;
    private PendingChat pendingNewChat;

    public Focus focus() {
        return focus;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public String composer() {
        return composer;
    }

    /**
     * Idempotency key of the send awaiting its authoritative broadcast,
     * or null when nothing is pending.
     */
    public UUID pendingSend() {
        return pendingSend;
    }

    public boolean helpVisible() {
        return helpVisible;
    }

    public String status() {
        return status;
    }

    public void selectUp(int count) {
        if (count > 0) {
            selectedIndex = Math.max(0, selectedIndex - 1);
        }
    }

    public void selectDown(int count) {
        if (count > 0) {
            selectedIndex = Math.min(count - 1, selectedIndex + 1);
        }
    }

    public void selectConversation(int index) {
        selectedIndex = Math.max(0, index);
    }

    public void toggleFocus() {
        focus = focus == Focus.CONVERSATIONS ? Focus.COMPOSER : Focus.CONVERSATIONS;
    }

    public void focusConversations() {
        focus = Focus.CONVERSATIONS;
    }

    public void focusComposer() {
        focus = Focus.COMPOSER;
    }

    public void appendToComposer(char c) {
        if (composer.length() < MAX_COMPOSER_LENGTH) {
            composer += c;
        }
    }

    public void backspaceComposer() {
        if (!composer.isEmpty()) {
            composer = composer.substring(0, composer.length() - 1);
        }
    }

    public void clearComposer() {
        composer = "";
    }

    public void setPendingSend(UUID requestId) {
        pendingSend = requestId;
    }

    public void clearPendingSend() {
        pendingSend = null;
    }

    public void toggleHelp() {
        helpVisible = !helpVisible;
    }

    public void closeHelp() {
        helpVisible = false;
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    public View view() {
        return view;
    }

    public void enterSearch() {
        view = View.SEARCH;
        searchFocus = SearchFocus.INPUT;
        pendingNewChat = null;
    }

    public void enterRequests() {
        view = View.REQUESTS;
        requestSelectedIndex = 0;
        pendingNewChat = null;
    }

    public void exitToChat() {
        view = View.CHAT;
    }

    public SearchFocus searchFocus() {
        return searchFocus;
    }

    public void toggleSearchFocus() {
        searchFocus = searchFocus == SearchFocus.INPUT ? SearchFocus.SEND : SearchFocus.INPUT;
    }

    public void focusSearchInput() {
        searchFocus = SearchFocus.INPUT;
    }

    public String searchInput() {
        return searchInput;
    }

    public void appendToSearch(char c) {
        if (searchInput.length() < MAX_SEARCH_LENGTH) {
            searchInput += c;
        }
    }

    public void backspaceSearch() {
        if (!searchInput.isEmpty()) {
            searchInput = searchInput.substring(0, searchInput.length() - 1);
        }
    }

    public void clearSearch() {
        searchInput = "";
        searchFocus = SearchFocus.INPUT;
    }

    public RequestSection requestSection() {
        return requestSection;
    }

    public void toggleRequestSection() {
        requestSection = requestSection == RequestSection.INCOMING
                ? RequestSection.OUTGOING
                : RequestSection.INCOMING;
        requestSelectedIndex = 0;
    }

    public int requestSelectedIndex() {
        return requestSelectedIndex;
    }

    public void selectRequestUp(int count) {
        if (count > 0) {
            requestSelectedIndex = Math.max(0, requestSelectedIndex - 1);
        }
    }

    public void selectRequestDown(int count) {
        if (count > 0) {
            requestSelectedIndex = Math.min(count - 1, requestSelectedIndex + 1);
        }
    }

    public void clampRequestSelection(int count) {
        if (count <= 0) {
            requestSelectedIndex = 0;
        } else {
            requestSelectedIndex = Math.min(requestSelectedIndex, count - 1);
        }
    }

    public SidebarTab sidebarTab() {
        return sidebarTab;
    }

    public void showConversationsTab() {
        sidebarTab = SidebarTab.CONVERSATIONS;
    }

    public void showFriendsTab() {
        sidebarTab = SidebarTab.FRIENDS;
        friendSelectedIndex = 0;
    }

    public void selectPreviousTab() {
        if (sidebarTab == SidebarTab.FRIENDS) {
            sidebarTab = SidebarTab.CONVERSATIONS;
        }
    }

    public void selectNextTab() {
        if (sidebarTab == SidebarTab.CONVERSATIONS) {
            sidebarTab = SidebarTab.FRIENDS;
            friendSelectedIndex = 0;
        }
    }

    public int friendSelectedIndex() {
        return friendSelectedIndex;
    }

    public void selectFriendUp(int count) {
        if (count > 0) {
            friendSelectedIndex = Math.max(0, friendSelectedIndex - 1);
        }
    }

    public void selectFriendDown(int count) {
        if (count > 0) {
            friendSelectedIndex = Math.min(count - 1, friendSelectedIndex + 1);
        }
    }

    public void clampFriendSelection(int count) {
        if (count <= 0) {
            friendSelectedIndex = 0;
        } else {
            friendSelectedIndex = Math.min(friendSelectedIndex, count - 1);
        }
    }

    public PendingChat pendingNewChat() {
        return pendingNewChat;
    }

    public void startNewChat(UUID userId, String username) {
        pendingNewChat = new PendingChat(userId, username);
    }

    public void clearNewChat() {
        pendingNewChat = null;
    }
}

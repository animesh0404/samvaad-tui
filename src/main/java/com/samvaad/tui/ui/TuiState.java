package com.samvaad.tui.ui;

import java.util.UUID;

/**
 * Mutable UI state for the TUI shell: selection, focus, composer buffer,
 * pending send, help overlay visibility, and the transient status line.
 */
public final class TuiState {

    public enum Focus {
        CONVERSATIONS,
        COMPOSER
    }

    static final int MAX_COMPOSER_LENGTH = 200;

    private Focus focus = Focus.CONVERSATIONS;
    private int selectedIndex;
    private String composer = "";
    private UUID pendingSend;
    private boolean helpVisible;
    private String status = "";

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

    public void toggleFocus() {
        focus = focus == Focus.CONVERSATIONS ? Focus.COMPOSER : Focus.CONVERSATIONS;
    }

    public void focusConversations() {
        focus = Focus.CONVERSATIONS;
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
}

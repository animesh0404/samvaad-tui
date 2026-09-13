package com.samvaad.tui.ui;

/**
 * Concise, user-safe TUI failure. Never carries credentials or tokens.
 */
public final class TuiException extends RuntimeException {

    public TuiException(String message) {
        super(message);
    }

    public TuiException(String message, Throwable cause) {
        super(message, cause);
    }
}

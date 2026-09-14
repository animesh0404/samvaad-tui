package com.samvaad.tui.realtime;

/**
 * Concise, user-safe realtime failure. Never carries credentials or tokens.
 */
public final class RealtimeException extends RuntimeException {

    public RealtimeException(String message) {
        super(message);
    }

    public RealtimeException(String message, Throwable cause) {
        super(message, cause);
    }
}

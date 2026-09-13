package com.samvaad.tui.api;

/**
 * Concise, user-safe API failure. Carries a machine-readable {@link Kind}
 * and the HTTP status code ({@code -1} when no response was received).
 * Never carries credentials or tokens.
 */
public final class SamvaadApiException extends RuntimeException {

    public enum Kind {
        AUTHENTICATION_FAILED,
        SERVER_UNAVAILABLE,
        HTTP_ERROR,
        MALFORMED_RESPONSE
    }

    private final Kind kind;
    private final int statusCode;

    public SamvaadApiException(Kind kind, int statusCode, String message) {
        super(message);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public SamvaadApiException(Kind kind, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public Kind kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }
}

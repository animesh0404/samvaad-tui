package com.samvaad.tui.session;

/**
 * Fail-closed error of the persistent authentication credential: unreadable
 * or corrupt files, insecure file permissions, or unwritable state.
 * Never carries credential material in the message.
 */
public class AuthStoreException extends RuntimeException {

    public AuthStoreException(String message) {
        super(message);
    }

    public AuthStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.samvaad.tui.bootstrap;

/**
 * Fail-closed error of the local E2EE runtime: wrong vault password,
 * corrupt or inconsistent local cryptographic state, missing interactive
 * input, or use of E2EE transport before the device is enrolled.
 *
 * <p>Never carries key material, passwords, or other secrets in the message.
 */
public class E2eeException extends RuntimeException {

    public E2eeException(String message) {
        super(message);
    }

    public E2eeException(String message, Throwable cause) {
        super(message, cause);
    }
}

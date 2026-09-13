package com.samvaad.tui.api;

/**
 * Raw HTTP response as seen by the transport layer.
 *
 * @param statusCode HTTP status code
 * @param body response body, may be empty but never null
 */
public record HttpResult(int statusCode, String body) {

    public HttpResult {
        if (body == null) {
            throw new IllegalArgumentException("Body must not be null.");
        }
    }
}

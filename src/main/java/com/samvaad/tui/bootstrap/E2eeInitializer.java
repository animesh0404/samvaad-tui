package com.samvaad.tui.bootstrap;

/**
 * Best-effort E2EE sender setup performed once after login. Implementations
 * must never break the plaintext flow: any failure degrades to
 * plaintext-only. Returns null when E2EE is unavailable this session.
 */
public interface E2eeInitializer {

    /**
     * Opens (or first-initializes) the local device and enrolls it with
     * the current session.
     *
     * @param serverUrl normalized server base URL
     * @param accessToken current access token, borrowed for these calls only
     * @return a ready sender, a disabled sender with the reason, or null
     */
    E2eeMessageSender initialize(String serverUrl, String accessToken);
}

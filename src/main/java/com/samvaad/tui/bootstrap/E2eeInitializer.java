package com.samvaad.tui.bootstrap;

import com.samvaad.tui.session.AuthSession;

/**
 * Best-effort E2EE setup performed once after login. Implementations
 * must never break the plaintext flow: any failure degrades to
 * plaintext-only. Returns null when E2EE is unavailable this session.
 */
public interface E2eeInitializer {

    /**
     * Opens (or first-initializes) the local device and enrolls it with
     * the current session.
     *
     * @param serverUrl normalized server base URL
     * @param auth current session (access token borrowed, session id used
     *             for binding checks; neither is persisted here)
     * @return the session setup (sender and/or inbox may each be null),
     *         or null when E2EE is unavailable this session
     */
    E2eeSetup initialize(String serverUrl, AuthSession auth);
}

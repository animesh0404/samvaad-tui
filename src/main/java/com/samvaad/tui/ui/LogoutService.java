package com.samvaad.tui.ui;

/**
 * Explicit user logout seam. Implemented by the bootstrap layer, which owns
 * the access token and the persistent credential; the UI only triggers it.
 * Revokes the server session and deletes the local credential while
 * retaining all E2EE state. Unchecked failures surface through the
 * existing API error model.
 */
public interface LogoutService {

    /**
     * Revokes the current server session and clears the persistent
     * authentication credential. E2EE vault, store, and device state are
     * left untouched.
     */
    void logout();
}

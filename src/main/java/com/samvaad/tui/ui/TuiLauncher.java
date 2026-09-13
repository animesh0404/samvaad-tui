package com.samvaad.tui.ui;

/**
 * Seam between bootstrap and the fullscreen UI so the startup flow stays
 * unit-testable without a real terminal. Carries display data only.
 */
public interface TuiLauncher {

    /**
     * Enters the fullscreen TUI and returns after a clean exit.
     *
     * @param session token-free session view for the UI
     * @throws TuiException when the terminal cannot be used
     */
    void launch(TuiSession session);
}

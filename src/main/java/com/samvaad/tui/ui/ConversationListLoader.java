package com.samvaad.tui.ui;

import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.model.ConversationEntry;
import java.util.List;

/**
 * Reloads the authoritative conversation list without exposing tokens
 * to UI code. Implemented outside {@code ui} where the authenticated
 * session lives; invoked from a worker thread so HTTP never blocks
 * the UI thread. Server order is preserved exactly.
 */
public interface ConversationListLoader {

    /**
     * Loads the caller's direct conversations in server order.
     *
     * @throws SamvaadApiException when loading fails
     */
    List<ConversationEntry> load();
}

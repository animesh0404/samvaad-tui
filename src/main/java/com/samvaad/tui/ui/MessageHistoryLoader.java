package com.samvaad.tui.ui;

import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.model.MessageEntry;
import java.util.List;
import java.util.UUID;

/**
 * Loads message history without exposing tokens to UI code. Implemented
 * outside {@code ui} where the authenticated session lives; invoked from
 * a worker thread so HTTP never blocks the UI thread.
 */
public interface MessageHistoryLoader {

    /**
     * Loads messages with {@code sequenceNumber > afterSequence}, ascending,
     * preserving the exact server order.
     *
     * @throws SamvaadApiException when loading fails
     */
    List<MessageEntry> load(UUID conversationId, long afterSequence, int limit);
}

package com.samvaad.tui.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Token-free, server-backed conversation state for the TUI lifetime.
 *
 * <p>Conversation order is exactly the server-provided order. Message lists
 * keep the exact server-provided ascending sequence order. All methods are
 * synchronized: history loads on a worker thread while the UI thread reads.
 */
public final class ConversationStore {

    public enum LoadStatus {
        NOT_LOADED,
        LOADING,
        LOADED,
        ERROR
    }

    private final UUID currentUserId;
    private final List<ConversationEntry> conversations;
    private final Map<UUID, List<MessageEntry>> messages = new HashMap<>();
    private final Map<UUID, Long> highestLoadedSequence = new HashMap<>();
    private final Map<UUID, LoadStatus> loadStatuses = new HashMap<>();
    private final Map<UUID, String> loadErrors = new HashMap<>();

    public ConversationStore(UUID currentUserId, List<ConversationEntry> conversations) {
        if (currentUserId == null) {
            throw new IllegalArgumentException("Current user id must not be null.");
        }
        if (conversations == null) {
            throw new IllegalArgumentException("Conversations must not be null.");
        }
        this.currentUserId = currentUserId;
        this.conversations = new ArrayList<>(conversations);
        for (ConversationEntry conversation : this.conversations) {
            loadStatuses.put(conversation.conversationId(), LoadStatus.NOT_LOADED);
        }
    }

    public synchronized UUID currentUserId() {
        return currentUserId;
    }

    public synchronized List<ConversationEntry> conversations() {
        return List.copyOf(conversations);
    }

    public synchronized LoadStatus statusOf(UUID conversationId) {
        return loadStatuses.getOrDefault(conversationId, LoadStatus.NOT_LOADED);
    }

    /**
     * Marks a conversation loading when nothing was loaded yet. Returns true
     * when the caller should start loading. Errored conversations stay
     * errored until replaced state arrives; there is no automatic retry,
     * so a failed load never turns into a request loop.
     */
    public synchronized boolean markLoading(UUID conversationId) {
        if (statusOf(conversationId) != LoadStatus.NOT_LOADED) {
            return false;
        }
        loadStatuses.put(conversationId, LoadStatus.LOADING);
        loadErrors.remove(conversationId);
        return true;
    }

    /**
     * Stores freshly loaded messages and records the highest sequence
     * actually present. Message order is kept exactly as provided.
     */
    public synchronized void putMessages(UUID conversationId, List<MessageEntry> loaded) {
        List<MessageEntry> stored = new ArrayList<>(loaded);
        messages.put(conversationId, stored);
        long highest = 0;
        for (MessageEntry message : stored) {
            highest = Math.max(highest, message.sequenceNumber());
        }
        highestLoadedSequence.put(conversationId, highest);
        loadStatuses.put(conversationId, LoadStatus.LOADED);
        loadErrors.remove(conversationId);
    }

    public synchronized void putError(UUID conversationId, String message) {
        loadStatuses.put(conversationId, LoadStatus.ERROR);
        loadErrors.put(conversationId, message);
    }

    public synchronized List<MessageEntry> messagesOf(UUID conversationId) {
        return List.copyOf(messages.getOrDefault(conversationId, List.of()));
    }

    /**
     * Highest sequence number actually present locally. Distinct from the
     * server-provided {@code lastSequenceNumber} high-water mark, which
     * never implies messages were loaded.
     */
    public synchronized long highestLoadedSequence(UUID conversationId) {
        return highestLoadedSequence.getOrDefault(conversationId, 0L);
    }

    public synchronized String errorOf(UUID conversationId) {
        return loadErrors.get(conversationId);
    }
}

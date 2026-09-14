package com.samvaad.tui.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Replaces the conversation list wholesale with a freshly loaded
     * authoritative server list, preserving the exact server-provided
     * order. Newly seen conversation IDs are registered as NOT_LOADED
     * so history loads lazily through the existing mechanism; message
     * state, high-water marks, and load state for conversation IDs no
     * longer present are pruned. Used after first-message creation,
     * when the server owns a conversation the client has never seen.
     */
    public synchronized void replaceConversations(List<ConversationEntry> fresh) {
        Set<UUID> seen = new HashSet<>();
        for (ConversationEntry entry : fresh) {
            seen.add(entry.conversationId());
            loadStatuses.putIfAbsent(entry.conversationId(), LoadStatus.NOT_LOADED);
        }
        conversations.clear();
        conversations.addAll(fresh);
        messages.keySet().retainAll(seen);
        highestLoadedSequence.keySet().retainAll(seen);
        loadStatuses.keySet().retainAll(seen);
        loadErrors.keySet().retainAll(seen);
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
        mergeMessages(conversationId, loaded);
    }

    /**
     * Merges messages by authoritative identity, keeping one entry per
     * message id in ascending server sequence order. New rows may come
     * from history pages, realtime broadcasts, or catch-up; the union
     * ordered by the server's own sequence key reproduces server order
     * without inventing values.
     */
    public synchronized void mergeMessages(UUID conversationId, List<MessageEntry> incoming) {
        Map<UUID, MessageEntry> byId = new LinkedHashMap<>();
        for (MessageEntry message : messages.getOrDefault(conversationId, List.of())) {
            byId.put(message.messageId(), message);
        }
        for (MessageEntry message : incoming) {
            if (message.messageId() != null) {
                byId.putIfAbsent(message.messageId(), message);
            }
        }
        List<MessageEntry> merged = new ArrayList<>(byId.values());
        merged.sort((left, right) -> Long.compare(left.sequenceNumber(), right.sequenceNumber()));
        messages.put(conversationId, merged);
        long highest = 0;
        for (MessageEntry message : merged) {
            highest = Math.max(highest, message.sequenceNumber());
        }
        highestLoadedSequence.put(conversationId, highest);
        loadStatuses.put(conversationId, LoadStatus.LOADED);
        loadErrors.remove(conversationId);
    }

    /**
     * Refreshes the server high-water mark from a server-supplied sequence
     * number only. Never derived arithmetically on the client.
     */
    public synchronized void updateHighWater(UUID conversationId, long sequenceNumber) {
        for (int i = 0; i < conversations.size(); i++) {
            ConversationEntry current = conversations.get(i);
            if (current.conversationId().equals(conversationId)
                    && sequenceNumber > current.lastSequenceNumber()) {
                conversations.set(i, new ConversationEntry(
                        current.conversationId(),
                        current.otherParticipantUserId(),
                        current.otherParticipantUsername(),
                        sequenceNumber,
                        current.updatedAt()));
                return;
            }
        }
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

    /**
     * Whether any locally held message carries the given idempotency key.
     * Used to reconcile a pending send against its authoritative broadcast
     * regardless of which conversation is currently selected.
     */
    public synchronized boolean containsRequestId(UUID requestId) {
        if (requestId == null) {
            return false;
        }
        for (List<MessageEntry> held : messages.values()) {
            for (MessageEntry message : held) {
                if (requestId.equals(message.requestId())) {
                    return true;
                }
            }
        }
        return false;
    }
}

package com.samvaad.tui.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-free, server-backed friend-request state for the TUI lifetime.
 *
 * <p>Holds the latest exact-username lookup result and the pending
 * incoming/outgoing request lists. List order is exactly the
 * server-provided {@code createdAt DESC} order; entries are never
 * re-sorted here. All methods are synchronized: refresh workers run
 * off the UI thread while the UI thread reads.
 *
 * <p>Deliberately separate from {@link ConversationStore}: message
 * state and friend-request state have different lifecycles and the
 * server exposes no join between them.
 */
public final class FriendRequestStore {

    public enum LoadStatus {
        NOT_LOADED,
        LOADING,
        LOADED,
        ERROR
    }

    private UserLookupEntry lookupResult;
    private LoadStatus lookupStatus = LoadStatus.NOT_LOADED;
    private String lookupError;

    private List<FriendRequestEntry> incoming = new ArrayList<>();
    private LoadStatus incomingStatus = LoadStatus.NOT_LOADED;
    private String incomingError;

    private List<FriendRequestEntry> outgoing = new ArrayList<>();
    private LoadStatus outgoingStatus = LoadStatus.NOT_LOADED;
    private String outgoingError;

    public synchronized UserLookupEntry lookupResult() {
        return lookupResult;
    }

    public synchronized LoadStatus lookupStatus() {
        return lookupStatus;
    }

    public synchronized String lookupError() {
        return lookupError;
    }

    /**
     * Marks a lookup in flight. The previous result stays visible
     * until the new result arrives or fails.
     */
    public synchronized void markLookupLoading() {
        lookupStatus = LoadStatus.LOADING;
        lookupError = null;
    }

    public synchronized void putLookupResult(UserLookupEntry result) {
        lookupResult = result;
        lookupStatus = LoadStatus.LOADED;
        lookupError = null;
    }

    public synchronized void putLookupError(String message) {
        lookupStatus = LoadStatus.ERROR;
        lookupError = message;
    }

    public synchronized void clearLookup() {
        lookupResult = null;
        lookupStatus = LoadStatus.NOT_LOADED;
        lookupError = null;
    }

    public synchronized List<FriendRequestEntry> incoming() {
        return List.copyOf(incoming);
    }

    public synchronized LoadStatus incomingStatus() {
        return incomingStatus;
    }

    public synchronized String incomingError() {
        return incomingError;
    }

    /**
     * Marks the incoming list refreshing. Existing entries stay
     * visible until the refresh completes or fails.
     */
    public synchronized void markIncomingLoading() {
        if (incomingStatus != LoadStatus.LOADING) {
            incomingStatus = LoadStatus.LOADING;
        }
        incomingError = null;
    }

    /**
     * Replaces the incoming list wholesale, preserving the exact
     * server-provided order.
     */
    public synchronized void putIncoming(List<FriendRequestEntry> entries) {
        incoming = new ArrayList<>(entries);
        incomingStatus = LoadStatus.LOADED;
        incomingError = null;
    }

    public synchronized void putIncomingError(String message) {
        incomingStatus = LoadStatus.ERROR;
        incomingError = message;
    }

    public synchronized List<FriendRequestEntry> outgoing() {
        return List.copyOf(outgoing);
    }

    public synchronized LoadStatus outgoingStatus() {
        return outgoingStatus;
    }

    public synchronized String outgoingError() {
        return outgoingError;
    }

    /**
     * Marks the outgoing list refreshing. Existing entries stay
     * visible until the refresh completes or fails.
     */
    public synchronized void markOutgoingLoading() {
        if (outgoingStatus != LoadStatus.LOADING) {
            outgoingStatus = LoadStatus.LOADING;
        }
        outgoingError = null;
    }

    /**
     * Replaces the outgoing list wholesale, preserving the exact
     * server-provided order.
     */
    public synchronized void putOutgoing(List<FriendRequestEntry> entries) {
        outgoing = new ArrayList<>(entries);
        outgoingStatus = LoadStatus.LOADED;
        outgoingError = null;
    }

    public synchronized void putOutgoingError(String message) {
        outgoingStatus = LoadStatus.ERROR;
        outgoingError = message;
    }
}

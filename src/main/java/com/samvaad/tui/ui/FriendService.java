package com.samvaad.tui.ui;

import com.samvaad.tui.api.SamvaadApiException;
import com.samvaad.tui.model.FirstMessage;
import com.samvaad.tui.model.FriendEntry;
import com.samvaad.tui.model.FriendRequestEntry;
import com.samvaad.tui.model.UserLookupEntry;
import java.util.List;
import java.util.UUID;

/**
 * User lookup and friend-request operations without exposing tokens
 * to UI code. Implemented outside {@code ui} where the authenticated
 * session lives; invoked from worker threads so HTTP never blocks
 * the UI thread. Friend requests have no realtime contract, so every
 * operation here is an HTTP call.
 */
public interface FriendService {

    /**
     * Looks up one user by exact username.
     *
     * @throws SamvaadApiException when lookup fails
     */
    UserLookupEntry lookup(String username);

    /**
     * Sends a friend request to the given exact username.
     *
     * @throws SamvaadApiException when sending fails
     */
    FriendRequestEntry sendRequest(String username);

    /**
     * Reloads the pending incoming requests in server order.
     *
     * @throws SamvaadApiException when loading fails
     */
    List<FriendRequestEntry> refreshIncoming();

    /**
     * Reloads the pending outgoing requests in server order.
     *
     * @throws SamvaadApiException when loading fails
     */
    List<FriendRequestEntry> refreshOutgoing();

    /**
     * Accepts the given pending request as its recipient.
     *
     * @throws SamvaadApiException when accepting fails
     */
    FriendRequestEntry accept(UUID requestId);

    /**
     * Rejects the given pending request as its recipient.
     *
     * @throws SamvaadApiException when rejecting fails
     */
    FriendRequestEntry reject(UUID requestId);

    /**
     * Cancels the given pending request as its sender.
     *
     * @throws SamvaadApiException when cancelling fails
     */
    FriendRequestEntry cancel(UUID requestId);

    /**
     * Reloads the authoritative accepted-friends list in server order.
     * Friendship is determined by the server; nothing here derives it
     * from request or conversation state.
     *
     * @throws SamvaadApiException when loading fails
     */
    List<FriendEntry> refreshFriends();

    /**
     * Sends the first message to a friend by exact username through the
     * REST first-message endpoint, which creates the conversation when
     * none exists. The caller supplies a fresh idempotency key; message
     * ID, conversation ID, sequence, and timestamp are server-owned.
     *
     * @return the authoritative persisted message with its
     *     authoritative conversation ID
     * @throws SamvaadApiException when sending fails
     */
    FirstMessage sendFirstMessage(String username, String content, UUID requestId);
}

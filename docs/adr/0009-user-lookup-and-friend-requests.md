# ADR-0009: User Lookup and Friend Requests

## Status

Accepted

## Context

Phase 6 adds the first social workflow to the TUI: finding a user by username and managing pending friend requests. The Samvaad Server is authoritative and exposes exact username lookup plus send/list/mutate friend-request contracts.

The server does **not** expose a friends-list, friendship-status, unfriend, blocking, friend-request realtime, or conversation-creation endpoint for the STOMP path. Accepting a request establishes friendship but does not create a conversation.

The TUI therefore must not infer an accepted-friends list from pending requests or conversations, and must not invent missing server contracts.

## Decision

1. Implement only the verified user lookup endpoint:
   `GET /api/users/lookup?username={username}`.
2. Treat lookup as exact username matching. The server performs trimming and case-insensitive matching; the TUI does not implement prefix, substring, fuzzy, pagination, or client-side user search.
3. Implement the six verified friend-request operations:
   - `POST /api/friend-requests`
   - `GET /api/friend-requests/incoming`
   - `GET /api/friend-requests/outgoing`
   - `POST /api/friend-requests/{requestId}/accept`
   - `POST /api/friend-requests/{requestId}/reject`
   - `POST /api/friend-requests/{requestId}/cancel`
4. Request IDs, timestamps, status transitions, duplicate detection, authorization, and persistence remain server-owned. The TUI does not generate request IDs, idempotency keys, or retry semantics.
5. Keep friend-request state in a dedicated `FriendRequestStore`, separate from `ConversationStore`, because the server exposes these as distinct domain concerns and accepted friendships are not equivalent to conversations.
6. Keep raw access tokens out of UI state and rendering. `AppBootstrap` owns authenticated API closures behind the token-free `FriendService` seam.
7. Perform lookup and friend-request HTTP work on background workers. Refresh pending incoming/outgoing lists explicitly after relevant actions and periodically while the requests view is active; do not block terminal input/rendering.
8. Preserve the server-provided ordering of incoming and outgoing pending requests. The client does not re-sort them.
9. Keep friend requests HTTP-only. No friend-request realtime subscription is introduced without a verified server contract.
10. Do not add a friends tab or friends list until the server exposes an authoritative accepted-friends read contract.

## Consequences

### Positive

- The TUI remains a thin client and does not recreate friendship business rules.
- Server ordering and lifecycle semantics remain authoritative.
- Token containment remains consistent with the conversation/realtime architecture.
- The UI can manage the complete verified pending-request lifecycle without coupling to transport details.

### Negative / limitations

- The TUI cannot currently show all accepted friends because the server does not provide a friends-list endpoint.
- Newly accepted friendships do not automatically appear as conversations because the server does not create conversations on acceptance.
- Pending request changes made by another client become visible through HTTP refresh rather than realtime.
- The current API error surface deliberately avoids exposing server response bodies, so some HTTP 409 cases have a combined user-facing explanation.

## Alternatives rejected

- **Derive friends from conversations:** rejected because friendship and conversation are separate server concepts and a friend may have no conversation.
- **Treat accepted requests as the friend list:** rejected because the pending incoming/outgoing endpoints no longer return accepted requests.
- **Invent a `/friends` endpoint:** rejected because the TUI consumes, rather than designs, the server API.
- **Use fuzzy username search:** rejected because the verified lookup contract is exact-match only.
- **Create a conversation when accepting a request:** rejected because that behavior is not provided by the server contract.

# ADR-0010: Friends Tab and Start-Chat Workflow

## Status

Accepted

## Context

Phase 6 established user lookup and friend-request management but deliberately did not invent an accepted-friends list or conversation-creation contract. Phase 7A adds the server-owned `GET /api/friends` read contract. A friend is a user whose relationship with the authenticated user is established by an accepted friend request.

The server also already exposes `POST /api/conversations/direct/messages`, which creates a direct conversation when one does not exist and persists the first message. There is no separate conversation-create endpoint.

## Decision

1. Add a `FRIENDS` sidebar tab beside `CONVERSATIONS` in the TUI.
2. While the sidebar has focus, Left/Right arrows switch between `CONVERSATIONS` and `FRIENDS`. Existing composer/search/request bindings remain unchanged.
3. Populate the Friends tab only from the authoritative `GET /api/friends` response. Keep friend state in a dedicated `FriendStore`, separate from `FriendRequestStore` and `ConversationStore`.
4. Preserve the server-provided friend ordering; the TUI does not re-sort the list.
5. Identify a selected friend by `userId`, never by username.
6. If the selected friend already has a known conversation in `ConversationStore`, open that conversation and reuse the existing history/realtime flow.
7. If no conversation is known, enter an explicit pending-new-chat state and use the existing `POST /api/conversations/direct/messages` endpoint for the first message, addressed by the friend's username and carrying a fresh client-generated UUID `requestId`.
8. The first-message response is authoritative persisted server state. The TUI does not fabricate message ID, conversation ID, sequence number, timestamp, or ordering metadata.
9. After successful first-message creation, merge the authoritative message, refresh the authoritative conversation list, reconcile `ConversationStore` using server order, select the returned `conversationId`, top up history through the existing history loader, and hand the selected conversation to the existing realtime subscription flow.
10. Friends HTTP work and first-message HTTP work run on background workers. Friends are refreshed when entering the tab and through an explicit `g` action; no friends polling or realtime contract is introduced.
11. Documentation remains owned by the project documentation gate rather than by the implementation agent.

## Consequences

### Positive

- The Friends tab reflects authoritative server friendship state rather than client inference.
- Existing conversations are reused instead of duplicated.
- New conversations use an already verified server capability rather than a fabricated endpoint.
- Server-owned IDs, ordering, timestamps, and sequence numbers remain authoritative.
- The existing Phase 5 realtime architecture remains the single realtime path.

### Negative / limitations

- A newly created conversation becomes visible in the conversation list after an additional authoritative list refresh.
- The Friends list is refreshed on tab entry or explicit request rather than through realtime updates.
- Existing conversation detection is limited to conversations currently present in the client's loaded conversation list; the first-message REST path handles the no-known-conversation case.
- Very narrow terminals may truncate the two-tab sidebar header.

## Alternatives rejected

- **Derive friends from pending/accepted friend-request state in the TUI:** rejected because the authoritative Friends API now owns that relationship read.
- **Create a synthetic conversation locally after the first message:** rejected because conversation metadata and ordering are server-owned.
- **Add a dedicated conversation-create endpoint:** rejected because the existing REST first-message contract already creates the conversation when needed.
- **Use username matching for conversation identity:** rejected because `userId` is the authoritative identity.
- **Add friend realtime or periodic polling:** rejected because no such requirement/verified contract exists in this phase.

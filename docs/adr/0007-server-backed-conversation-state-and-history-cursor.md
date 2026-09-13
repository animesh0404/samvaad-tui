# ADR-0007: Server-Backed Conversation State and Sequence-Based History

- Status: Accepted
- Date: 2026-09-14

## Context

Phase 4 replaces the Phase 3 preview inbox with the existing Samvaad Server's
conversation and message-history contracts. The server provides:

- `GET /api/conversations/direct?limit&offset` for participant-scoped direct
  conversation entries;
- `GET /api/conversations/direct/{conversationId}/messages?afterSequence&limit`
  for message history;
- deterministic conversation ordering (`updatedAt DESC`, then
  `conversationId ASC`);
- deterministic message ordering by ascending `sequenceNumber`;
- `lastSequenceNumber` on a conversation as a server-side high-water mark;
- no `hasMore` or cursor envelope for either response;
- zone-less `LocalDateTime` timestamps;
- no dedicated single-conversation lookup endpoint.

The TUI needs enough local state to render the server data and prepare for
future realtime delivery without recreating server sequencing or ordering
rules. In particular, a server high-water mark must not be confused with the
highest sequence the client has actually loaded.

## Decision

Use a small `ConversationStore` as the TUI's in-memory presentation state.

The store keeps:

- the conversation entries in exactly the order supplied by the server;
- message lists keyed by conversation id, preserving the server's sequence
  order;
- the server-provided `lastSequenceNumber` as conversation metadata;
- a separate `highestLoadedSequence` representing the highest message
  sequence actually present locally;
- explicit per-conversation history load status and error state.

Initial message history is loaded with `afterSequence=0` and `limit=20`.
The API seam retains the server cursor semantics for later paging, but Phase 4
does not introduce a load-more interaction.

The UI does not sort conversations or messages by timestamps, generate sequence
numbers, or infer missing messages. Server ordering and sequencing remain
authoritative.

History HTTP is executed on a daemon worker rather than the Lanterna render/
input loop. The UI receives a token-free `TuiSession` containing display
context, the conversation store, and the history-loading seam.

## Consequences

Positive:

- the TUI remains a thin consumer of server state;
- server ordering and sequence semantics are preserved exactly;
- high-water mark and locally loaded state remain unambiguous;
- the model is directly usable by a future realtime phase;
- HTTP latency cannot block terminal input/rendering;
- history loading is independently testable through a small seam.

Negative:

- the client maintains a small amount of duplicated in-memory state;
- explicit load-status handling is required for asynchronous history requests;
- message paging UI is deferred to a later phase.

This decision should be revisited only if the verified server contracts or a
concrete realtime/paging requirement require a different state model.

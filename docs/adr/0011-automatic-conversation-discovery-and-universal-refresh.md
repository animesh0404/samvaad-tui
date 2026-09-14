# ADR-0011: Automatic Conversation Discovery and Universal Manual Refresh

## Status

Accepted

## Context

Phase 7B allowed the TUI to start a new direct conversation from Friends, but a second client that did not yet know the new conversation ID could not discover it through the existing realtime subscription model. The verified server's REST first-message path persists the message and creates the conversation, but does not broadcast that REST-created first message through STOMP. The TUI therefore needs an authoritative conversation-list refresh to discover newly created conversations.

The same TUI also has several server-backed list views: conversations, Friends, and incoming/outgoing friend requests. Users need an immediate way to request fresh authoritative data instead of waiting for periodic or view-entry refreshes.

## Decision

1. Refresh the authoritative conversation list in the background every 5 seconds while the fullscreen TUI is active.
2. Reuse the existing `ConversationListLoader` and `GET /api/conversations/direct?limit&offset`; do not create a second discovery/loading path.
3. Prevent overlapping conversation refreshes with a guarded background worker. Automatic refresh failures preserve the currently visible valid conversation list and remain silent.
4. Preserve conversation selection by authoritative `conversationId` across server-list reordering. A newly discovered conversation never steals focus or automatically opens.
5. Once a discovered conversation is selected, reuse the existing history loader and STOMP subscription machinery. Polling is only for conversation discovery, not message delivery.
6. Introduce `F5` as the universal manual refresh key. It dispatches centrally to the existing refresh mechanism for the active server-backed view: conversations, Friends, incoming/outgoing requests, or the current search lookup.
7. `F5` is additive; existing `g` refresh shortcuts remain where already established.
8. Manual refresh is asynchronous and non-blocking, preserves valid visible data on failure, and does not restart the TUI or steal focus.
9. Lifecycle and background work remain inside the existing `TuiApp` coordination model; refresh workers are daemon threads and do not introduce a new scheduler/executor subsystem.

## Consequences

### Positive

- A second running TUI discovers newly created conversations without requiring manual navigation through Friends.
- Users have one predictable manual refresh convention across server-backed views.
- Existing API, store, history, and realtime boundaries are reused.
- Selection and focus remain stable during authoritative list reconciliation.
- Refresh failures do not erase usable client state.

### Negative / limitations

- Automatic conversation discovery has up to approximately a 5-second discovery delay.
- Conversation discovery remains polling rather than realtime because the verified server contract does not provide a suitable discovery event/subscription.
- Each conversation refresh is an additional authenticated HTTP GET.
- F5 in search reuses the existing lookup action rather than introducing a separate search refresh implementation.

## Alternatives rejected

- **Add a server broadcast for REST-created first messages:** rejected for this phase because the recipient still would not know the new conversation ID and therefore could not have the required conversation subscription.
- **Invent a new realtime discovery channel:** rejected because no such server contract exists.
- **Refresh on every render tick:** rejected because it creates unnecessary HTTP traffic and couples rendering to network activity.
- **Open newly discovered conversations automatically:** rejected because background discovery must never steal user focus.
- **Implement separate F5 HTTP logic per screen:** rejected because refresh should be one TUI-level convention delegating to existing loaders/services.

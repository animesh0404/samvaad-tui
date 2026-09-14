# Development Guide

## Toolchain

- Java 25 LTS
- Gradle via the checked-in Gradle Wrapper
- Git

No system Gradle installation is required.

## Build and test

```bash
./gradlew build
./gradlew test
./gradlew installDist
```

For the fullscreen TUI, use the installed application launcher in a real terminal:

```bash
build/install/samvaad-tui/bin/samvaad-tui --server http://localhost:8080 --username alice
```

`./gradlew run` is useful for CLI/non-interactive behavior but is not the primary way to run the fullscreen shell.

## TUI development

Phase 3 established the Lanterna fullscreen shell. Phase 4 replaced preview data with server-backed conversation/message state. Phase 5 adds message sending and realtime delivery. Phase 6 adds exact user lookup and pending friend-request workflows. Phase 7 adds the authoritative Friends tab, start-chat workflow, automatic conversation discovery, and universal manual refresh.

Responsibilities:

- `TuiApp` owns terminal lifecycle, the polling render/input loop, and background worker coordination.
- `TuiController` owns keyboard-to-state transitions and central refresh dispatch.
- `TuiRenderer` owns terminal presentation, including active-pane focus, messages, send status, lookup results, request panels, Friends, and help.
- `TuiSession` is a token-free bundle of UI display context, server-backed state, history loading, realtime operations, and social service seams.
- `ConversationStore` owns in-memory authoritative conversation/message presentation state, preserves server ordering, and supports wholesale authoritative conversation-list replacement.
- `FriendRequestStore` separately owns lookup and pending request presentation state.
- `FriendStore` separately owns the authoritative friends list from `GET /api/friends`.
- `ConversationListLoader` is the shared authoritative conversation-list loading seam used by first-message reconciliation, automatic discovery, and manual conversation refresh.
- `MessageHistoryLoader` keeps history loading behind a testable seam.
- `RealtimeManager` owns connection lifecycle, subscriptions, sending, bounded reconnect, and catch-up.

## Current bindings

```text
Up / Down / k / j  select active sidebar item
Left / Right       switch CONVERSATIONS / FRIENDS when sidebar is focused
Tab                switch focus / request section
Enter              open selected item / composer or send / select friend or lookup action
F5                 refresh authoritative data for the active server-backed view
/                  enter exact username search
g                  existing Friends/request refresh shortcut where supported
r                  open friend requests
F1 / ?              help
Esc                 close help / leave social mode / cancel pending new chat
F10 / Ctrl+C        quit
q (conversation/friends list) quit
```

`F5` is a TUI-wide manual refresh convention. `TuiController` dispatches it centrally rather than duplicating HTTP logic in each screen. In conversations it uses the same refresh path as automatic discovery; in Friends and request views it uses the existing loaders; in search it reuses the existing exact-username lookup action. F5 is asynchronous, does not restart the TUI, and does not steal selection or focus. Existing `g` refresh behavior remains additive.

## Conversation discovery and refresh

While the fullscreen TUI is active, the conversation list is checked every 5 seconds. When due, `TuiApp` starts a guarded daemon worker that calls the existing `ConversationListLoader`, which in turn uses the verified `GET /api/conversations/direct?limit&offset` contract. No HTTP occurs on the render/input thread and overlapping conversation refreshes are prevented.

Successful refreshes replace the authoritative conversation list while preserving the selected conversation by `conversationId` across reorderings. Newly discovered conversations appear in server order but are not automatically opened or selected. If a selected conversation disappears, selection falls back to a clamped index; an empty result does not fabricate selection. Automatic refresh failures preserve the visible valid conversation list and remain silent.

When a discovered conversation is later selected, the existing lazy history loader and STOMP subscription flow handle it. Conversation polling is discovery-only and does not replace realtime message delivery.

The refresh workers are daemon threads and do not introduce a scheduler/executor subsystem. They do not keep the JVM alive after the TUI lifecycle exits.

## Friends and friend requests

The Friends tab is populated only from `GET /api/friends`, preserving server order. It refreshes on tab entry and through the existing `g` shortcut; there is no Friends polling or friend realtime subscription.

Friend-request HTTP work runs on background workers and existing request refresh behavior remains in place, including periodic refresh while the request view is active. Incoming/outgoing lists remain server-authoritative and newest-first.

Selecting a friend resolves a known conversation by authoritative `userId`. If none is known, the TUI enters a pending-new-chat state and sends the first message through the existing REST direct-message contract. The server-created conversation and persisted message are authoritative; after success the existing conversation reconciliation, history, and realtime flow is reused.

## HTTP and realtime behavior

Verified conversation paths:

```text
GET  /api/conversations/direct?limit&offset
GET  /api/conversations/direct/{conversationId}/messages?afterSequence&limit
POST /api/conversations/direct/messages
```

Verified social paths:

```text
GET  /api/users/lookup?username={username}
POST /api/friend-requests
GET  /api/friend-requests/incoming
GET  /api/friend-requests/outgoing
POST /api/friend-requests/{requestId}/accept
POST /api/friend-requests/{requestId}/reject
POST /api/friend-requests/{requestId}/cancel
GET  /api/friends
```

Realtime remains:

```text
WebSocket /ws
STOMP SEND /app/chat.send
STOMP SUBSCRIBE /topic/conversations/{conversationId}
```

The same access JWT is used for authenticated HTTP and STOMP. The TUI does not generate server-owned message IDs, conversation IDs, sequence numbers, timestamps, sender identity, or friend-request IDs.

## Testing approach

API tests use the `HttpTransport` seam and fake transports. Model tests cover server-order preservation, authoritative message merge/deduplication, high-water versus locally loaded sequence, Friends/request state, and conversation-list replacement. TUI tests cover navigation, focus, social workflows, F5 dispatch, background conversation refresh, selection preservation, failure preservation, first-message reconciliation, and lifecycle behavior. Realtime tests cover transport destinations, subscriptions, authoritative message merge, reconnect, catch-up, and disconnect without requiring a live socket.

Automatic refresh tests should use injected clocks/schedulers or equivalent deterministic seams rather than sleeping for the production 5-second interval. Do not introduce an interactive PTY smoke-test harness.

A live smoke test should use a real terminal. Cross-client manual verification should include creating a new chat from one TUI while another remains on Conversations, confirming discovery within the refresh interval, then selecting the discovered conversation and verifying history/realtime behavior.

## Dependency policy

Keep the client dependency footprint small. Spring WebSocket/Messaging are client-side protocol libraries only; the TUI does not become a Spring Boot application or host a server. Do not introduce persistence, ORM, embedded server, broker, caching, offline sync, or another framework without a concrete requirement.

## Implementation workflow

For each phase:

1. establish scope
2. verify relevant Samvaad Server contracts
3. implement the client behavior
4. run tests/build
5. inspect the actual implementation
6. perform manual smoke verification where applicable
7. reconcile documentation
8. update the root `README.md` as part of the same documentation gate
9. commit and push
10. verify repository state

ChatGPT owns primary documentation and ADR authoring. Interactive smoke testing is a manual responsibility; automated tests should remain deterministic and non-interactive.

# Architecture

## Purpose

`samvaad-tui` is a thin terminal client for the existing Samvaad Server. The server remains authoritative for authentication, authorization, business rules, persistence, domain state, sequencing, idempotency, and other server-owned behavior.

## Boundaries

```text
+----------------------+       HTTP / realtime       +----------------------+
|     samvaad-tui      | --------------------------> |   Samvaad Server     |
|                      |                             |                      |
| CLI / TUI            |                             | Auth / Authz          |
| client state         |                             | Domain rules          |
| presentation         |                             | Persistence           |
| API clients          |                             | Realtime contracts    |
+----------------------+                             +----------------------+
```

The TUI owns terminal interaction, client-side presentation state, session state, transport adaptation, and invocation of server contracts. It must not duplicate server business logic or invent undocumented endpoints/payloads.

## Current package responsibilities

- `cli` — picocli command-line entry points and command handling.
- `bootstrap` — startup orchestration, console prompting, authentication, conversation loading, realtime lifecycle, friend-service construction, and TUI lifecycle orchestration.
- `config` — application configuration and resolution.
- `auth` — authentication credential handling and decoding the server-issued JWT `sub` for authenticated-user display attribution.
- `api` — HTTP transport, API clients, exceptions, and server DTOs.
- `session` — authenticated client session state, including the authenticated user id derived from the server-issued JWT.
- `realtime` — WebSocket/STOMP transport seam and realtime lifecycle management.
- `model` — server-backed conversation/message state plus user-lookup, friend-request, and friends presentation state for the TUI lifetime.
- `ui` — Lanterna terminal rendering, navigation, interaction, terminal lifecycle, and token-free service seams.

The UI must not construct HTTP requests or STOMP frames directly.

## TUI shell architecture

Phase 3 established the Lanterna UI boundary. Phase 4 replaced preview data with server-backed state. Phase 5 adds realtime transport and message sending. Phase 6 adds user lookup and pending friend-request workflows. Phase 7 adds an authoritative Friends tab, first-chat workflow, automatic conversation discovery, and universal manual refresh:

```text
AppBootstrap
    |
    +--> AuthApiClient -------------> Samvaad Server
    +--> ConversationApiClient ----> Samvaad Server
    +--> UserLookupApiClient ------> Samvaad Server
    +--> FriendRequestApiClient ---> Samvaad Server
    +--> FriendsApiClient ---------> Samvaad Server
    +--> RealtimeManager ----------> WebSocket/STOMP --> Samvaad Server
    |
    +--> TuiSession
             |
             v
          TuiApp
          / |  \
 TuiController | TuiRenderer
      |        |
   TuiState  Lanterna Screen
      |\
      +-----> ConversationStore
      +-----> FriendRequestStore
      +-----> FriendStore
```

`TuiLauncher` is the bootstrap-facing seam. `TuiApp` owns terminal lifecycle, the render/input loop, and background work coordination. `TuiController` translates key strokes into state transitions. `TuiRenderer` renders only client-side display state. `TuiSession` provides the UI with display context, server-backed stores, history loading, realtime operations, and the token-free `FriendService` without exposing raw access/refresh tokens.

`ConversationApiClient` implements the verified conversation-list and message-history reads plus the verified first-message REST operation. There is no single-conversation lookup endpoint and no dedicated conversation-create endpoint; the client does not invent either.

`ConversationStore` preserves server-provided conversation ordering and message sequence ordering. It separates the server-provided `lastSequenceNumber` high-water mark from `highestLoadedSequence`. Realtime messages and authoritative REST first-message responses are merged by server-owned message identity and sequence. Authoritative conversation-list replacement is also used by background discovery and manual refresh.

`FriendRequestStore` is deliberately separate from `ConversationStore`. `FriendStore` is a separate authoritative friends-list store populated only by `GET /api/friends` and preserving server order.

When a friend is selected, `TuiApp` resolves an existing conversation by the friend's authoritative `userId`. If none is known, the UI enters a pending-new-chat state and sends the first message through the verified direct-message REST operation. After success, the authoritative response drives conversation reconciliation, selection, history, and realtime handoff.

## Refresh and discovery architecture

Phase 7C adds two refresh mechanisms over the existing server-backed loaders:

- **Automatic conversation discovery:** while the fullscreen TUI is active, `TuiApp` checks every 5 seconds and, when due, starts one guarded daemon worker using the existing `ConversationListLoader`. This is an authoritative list refresh, not a message polling path. Overlapping refreshes are prevented. A failed background refresh leaves the visible conversation list unchanged.
- **Universal manual refresh:** `F5` is handled centrally by `TuiController`. It dispatches to the existing refresh mechanism for the active server-backed view: conversations, Friends, friend requests, or exact-username search. Manual refresh is asynchronous and does not restart the TUI or steal focus. Existing `g` refresh shortcuts remain additive where established.

Conversation selection is preserved by `conversationId` across authoritative list reordering. A newly discovered conversation is inserted according to server ordering but is not automatically selected or opened. Once the user selects it, the existing history loader and realtime subscription machinery takes over. Polling therefore solves conversation discovery without creating a second realtime path.

Refresh workers are daemon threads and use the existing `TuiApp` lifecycle model; no scheduler/executor subsystem is introduced.

## HTTP architecture

`AuthApiClient`, `ConversationApiClient`, `UserLookupApiClient`, `FriendRequestApiClient`, and `FriendsApiClient` depend on `HttpTransport`. `JdkHttpTransport` implements that boundary using `java.net.http.HttpClient`. Jackson handles JSON serialization/deserialization, including Java `LocalDateTime` values returned by the server.

The verified conversation API remains:

```text
GET  /api/conversations/direct?limit&offset
GET  /api/conversations/direct/{conversationId}/messages?afterSequence&limit
POST /api/conversations/direct/messages
```

The verified social APIs remain:

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

The TUI preserves server ordering and does not invent IDs, timestamps, sequence numbers, or status transitions.

## Realtime architecture

The existing Phase 5 realtime boundary remains unchanged:

```text
TuiApp / TuiController
        |
        v
 RealtimeManager -> RealtimeClient -> SpringRealtimeClient
        |
        v
 WebSocket + STOMP -> Samvaad Server /ws
```

The manager subscribes to `/topic/conversations/{conversationId}` and sends to `/app/chat.send`. Reconnect remains bounded and uses HTTP history catch-up. Conversation polling is discovery-only; subsequent messages use the existing STOMP path.

## Authentication/session flow

Login establishes the in-memory authenticated session, conversation state, realtime manager, and token-free social service. Realtime disconnect occurs before HTTP logout. The same server-issued access JWT is used for authenticated HTTP and realtime operations.

## Error handling

The API layer avoids exposing credentials or sensitive response bodies. Social and conversation refresh failures are surfaced through existing view-specific state/status handling. In particular, automatic conversation refresh failures do not clear the currently visible valid list; explicit F5 failures may surface a concise status message.

## Design principles

1. Server authority over client duplication.
2. Explicit boundaries over framework-heavy abstraction.
3. Small, intentional client-side state.
4. In-memory authentication state until a concrete persistence requirement exists.
5. Protocol details isolated inside API/realtime layers.
6. UI independent of transport implementation.
7. Implement only against verified server contracts.
8. Keep terminal lifecycle and presentation concerns inside the UI boundary.
9. Preserve server ordering and sequencing semantics rather than recreating them client-side.
10. Reconcile client-visible send state only from authoritative server messages/responses.
11. Keep friendship state separate from conversation state.
12. Do not infer or invent social relationships when the server provides an authoritative Friends API.
13. Reuse the existing first-message conversation-creation contract rather than inventing a dedicated conversation-create API.
14. Use one authoritative refresh mechanism per server-backed resource and expose universal manual refresh through central TUI dispatch.
15. Never let background discovery steal user selection or focus.

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
- `bootstrap` — startup orchestration, console prompting, authentication, conversation loading, realtime lifecycle, and TUI lifecycle orchestration.
- `config` — application configuration and resolution.
- `auth` — authentication credential handling and decoding the server-issued JWT `sub` for authenticated-user display attribution.
- `api` — HTTP transport, API clients, exceptions, and server DTOs.
- `session` — authenticated client session state, including the authenticated user id derived from the server-issued JWT.
- `realtime` — WebSocket/STOMP transport seam and realtime lifecycle management.
- `model` — server-backed conversation and message presentation state for the TUI lifetime.
- `ui` — Lanterna terminal rendering, navigation, interaction, terminal lifecycle, and the history-loading seam.

The UI must not construct HTTP requests or STOMP frames directly.

## TUI shell architecture

Phase 3 established the Lanterna UI boundary. Phase 4 replaced preview data with server-backed state. Phase 5 adds realtime transport and message sending without moving protocol mechanics into the UI:

```text
AppBootstrap
    |
    +--> AuthApiClient -------------> Samvaad Server
    |
    +--> ConversationApiClient ----> Samvaad Server
    |
    +--> RealtimeManager
    |       |
    |       +--> RealtimeClient ----> WebSocket/STOMP --> Samvaad Server
    |
    +--> TuiSession
             |
             v
          TuiApp
          / |  \
 TuiController | TuiRenderer
      |        |
   TuiState  Lanterna Screen
      |
 ConversationStore
   /          \
conversations  messagesByConversation
```

`TuiLauncher` is the bootstrap-facing seam. `TuiApp` owns terminal lifecycle, the render/input loop, and background work coordination. `TuiController` translates key strokes into state transitions. `TuiRenderer` renders only client-side display state. `TuiSession` provides the UI with display context, the server-backed conversation store, history loading, and realtime operations without exposing raw access/refresh tokens.

`ConversationApiClient` implements only the verified conversation-list and message-history reads. There is no single-conversation lookup endpoint in the server contract, and the client does not invent one.

`ConversationStore` preserves server-provided conversation ordering and message sequence ordering. It separates the server-provided `lastSequenceNumber` high-water mark from `highestLoadedSequence`, which records what the client has actually loaded locally. Realtime messages are merged by server-owned message identity and sequence, while request IDs provide send correlation.

History loading is initiated lazily when a conversation is selected. `TuiApp` runs HTTP calls on daemon workers so terminal input/rendering is not blocked. Realtime callbacks arrive on transport threads and update synchronized client state; the polling render loop repaints background changes while the UI is idle.

The renderer authoritatively paints cells within its owned regions, including interior spaces, so stale characters are not left behind when overlays close or content changes. Help geometry is derived from content, padded, and safely clamped to terminal dimensions. Resize events trigger Lanterna's normal full-redraw path. Help input handling accounts for terminal escape-sequence edge cases.

## HTTP architecture

`AuthApiClient` and `ConversationApiClient` depend on `HttpTransport`. `JdkHttpTransport` implements that boundary using `java.net.http.HttpClient`. Jackson handles JSON serialization/deserialization, including Java `LocalDateTime` values returned by the server.

The conversation API client exposes the exact verified reads:

```text
GET /api/conversations/direct?limit&offset
GET /api/conversations/direct/{conversationId}/messages?afterSequence&limit
```

Conversation paging is offset-based. Message history uses the server's sequence cursor: only messages with `sequenceNumber > afterSequence` are returned, in ascending sequence order. Phase 4 loads initial history with `afterSequence=0&limit=20`.

The API layer preserves server ordering and does not sort by timestamps. Server timestamps are zone-less `LocalDateTime` values and are displayed without timezone conversion.

## Realtime architecture

Phase 5 introduces a deliberately small realtime boundary:

```text
TuiApp / TuiController
        |
        v
 RealtimeManager
        |
        v
 RealtimeClient
        |
        v
 SpringRealtimeClient
        |
        v
 WebSocket + STOMP
        |
        v
 Samvaad Server /ws
```

`SpringRealtimeClient` adapts Spring's `WebSocketStompClient` and standard WebSocket client to the server's verified STOMP contract. It is not a Spring Boot application and does not create an embedded server or application context.

The client derives `ws://` or `wss://` from the configured HTTP(S) server URL and connects to `/ws`. STOMP CONNECT carries the same access JWT used by authenticated HTTP. The manager subscribes to `/topic/conversations/{conversationId}` and sends chat messages to `/app/chat.send`.

The transport does not perform its own retries. `RealtimeManager` owns bounded reconnect policy. On an unexpected connection loss it reconnects with the same access token, restores the selected conversation subscription, and uses the HTTP history seam for catch-up from `highestLoadedSequence`. This avoids treating a realtime socket as the authoritative persistence layer.

Incoming broadcasts are mapped to `MessageEntry` and merged into `ConversationStore` using server-owned message ID and sequence. The store deduplicates messages and preserves ascending sequence order. The client never generates message IDs, sequence numbers, timestamps, or sender identity.

Sending is deliberately non-optimistic. The client creates a fresh UUID `requestId`, sends the verified payload, and displays a pending `Sending...` state. The pending send becomes `Sent` only when an authoritative broadcast carrying the matching request ID is observed. A failed synchronous handoff surfaces an error rather than fabricating success; a silently dropped send remains pending because no authoritative success has been observed.

The server's `/user/queue/errors` destination is intentionally not subscribed because that wiring was not established by the verified contract. Transport ERROR frames and session callbacks provide the currently supported error path.

## Authentication/session flow

```text
CLI
  -> credentials
  -> AuthApiClient
  -> POST /api/auth/login
  <- accessToken + refreshToken + expiresIn + sessionId
  -> AuthSession(userId derived from JWT sub)
  -> SessionState(AUTHENTICATED)
  -> ConversationApiClient
  -> conversation list
  -> RealtimeManager.connect(accessToken)
  -> TuiSession
  -> fullscreen TUI
  -> selected conversation
  -> history + realtime subscription
  -> send / receive messages
  -> exit
  -> realtime disconnect
  -> AuthApiClient
  -> POST /api/auth/logout
  -> local SessionState cleared
```

Realtime disconnect happens before HTTP logout. The same authenticated session/JWT is used for both protocols.

## Session model

`SessionState` represents authenticated versus unauthenticated client state. `AuthSession` contains the server-issued access token, refresh token, session ID, expiry duration, acquisition timestamp, and authenticated user id derived from the JWT `sub` claim.

The client only decodes the JWT payload to obtain the server-defined user id for local sender attribution. It does not perform client-side JWT signature verification; authentication validity remains a server responsibility.

Authentication state and realtime credentials are currently in memory only. There is no local token database or credential store.

## Error handling

The API layer must avoid exposing credentials or sensitive response bodies. Authentication/runtime failures are surfaced to the CLI as failure status `1`; usage/validation failures use status `2`. TUI history failures are translated into explicit loading/error states while server logout still runs.

Realtime errors are represented by token-free notices. Reconnect is bounded rather than infinite. The current server contract does not provide a verified client-visible `/user/queue/errors` subscription, so the TUI does not invent one.

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
10. Reconcile client-visible send state only from authoritative server messages.

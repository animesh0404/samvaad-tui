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
- `bootstrap` — startup orchestration, console prompting, authentication, conversation loading, and TUI lifecycle orchestration.
- `config` — application configuration and resolution.
- `auth` — authentication credential handling and decoding the server-issued JWT `sub` for authenticated-user display attribution.
- `api` — HTTP transport, API clients, exceptions, and server DTOs.
- `session` — authenticated client session state, including the authenticated user id derived from the server-issued JWT.
- `realtime` — reserved for future WebSocket/STOMP integration.
- `model` — server-backed conversation and message presentation state for the TUI lifetime.
- `ui` — Lanterna terminal rendering, navigation, interaction, terminal lifecycle, and the history-loading seam.

The UI must not construct HTTP requests or STOMP frames directly.

## TUI shell architecture

Phase 3 established the Lanterna UI boundary. Phase 4 replaces the preview data with server-backed state:

```text
AppBootstrap
    |
    +--> AuthApiClient -------------> Samvaad Server
    |
    +--> ConversationApiClient ----> Samvaad Server
    |
    +--> TuiSession
             |
             v
          TuiApp
          /    \
 TuiController  TuiRenderer
      |             |
   TuiState     Lanterna Screen
      |
 ConversationStore
   /          \
conversations  messagesByConversation
```

`TuiLauncher` is the bootstrap-facing seam. `TuiApp` owns terminal lifecycle and the render/input loop. `TuiController` translates key strokes into state transitions. `TuiRenderer` renders only client-side display state. `TuiSession` provides the UI with display context, the server-backed conversation store, and a history-loading seam; raw access/refresh tokens remain outside the UI.

`ConversationApiClient` implements only the verified conversation-list and message-history reads. There is no single-conversation lookup endpoint in the server contract, and Phase 4 does not invent one.

`ConversationStore` preserves the server-provided conversation order and message sequence order. It deliberately separates the server-provided `lastSequenceNumber` high-water mark from `highestLoadedSequence`, which records what the client has actually loaded locally. This distinction is important for the future realtime phase.

History loading is initiated lazily when a conversation is first selected. `TuiApp` runs the HTTP call on a daemon worker so the terminal render/input loop is not blocked. Loading, loaded-empty, and error states remain client-side presentation state; server data remains authoritative.

The renderer authoritatively paints cells within its owned regions, including
interior spaces, so stale characters are not left behind when overlays close
or content changes. Help geometry is derived from content, padded, and safely
clamped to terminal dimensions. Resize events trigger Lanterna's normal
full-redraw path. Help input handling also accounts for terminal escape-sequence
edge cases so an unexpected merged key cannot strand the UI in the help state.

## Current HTTP architecture

`AuthApiClient` and `ConversationApiClient` depend on `HttpTransport`. `JdkHttpTransport` implements that boundary using `java.net.http.HttpClient`. Jackson handles JSON serialization/deserialization, including Java `LocalDateTime` values returned by the server.

The conversation API client exposes the exact verified reads:

```text
GET /api/conversations/direct?limit&offset
GET /api/conversations/direct/{conversationId}/messages?afterSequence&limit
```

Conversation paging is offset-based. Message history uses the server's sequence cursor: only messages with `sequenceNumber > afterSequence` are returned, in ascending sequence order. Phase 4 loads the initial history with `afterSequence=0&limit=20`; no load-more UI is added yet.

The API layer preserves server ordering and does not perform client-side timestamp sorting. Server timestamps are zone-less `LocalDateTime` values and are displayed without timezone conversion.

This keeps protocol mechanics out of command/UI code and provides a small seam for unit testing API behavior without a live server.

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
  -> TuiSession
  -> fullscreen TUI
  -> selected conversation
  -> ConversationApiClient via history loader
  -> message history
  -> exit
  -> AuthApiClient
  -> POST /api/auth/logout
  -> local SessionState cleared
```

Logout remains outside the TUI presentation layer and is server-authoritative.

## Session model

`SessionState` represents authenticated versus unauthenticated client state. `AuthSession` contains the server-issued access token, refresh token, session ID, expiry duration, acquisition timestamp, and authenticated user id derived from the JWT `sub` claim.

The client only decodes the JWT payload to obtain the server-defined user id for local sender attribution. It does not perform client-side JWT signature verification; authentication validity remains a server responsibility.

Authentication state is currently in memory only. There is no local token database or credential store.

## Error handling

The API layer must avoid exposing credentials or sensitive response bodies. Authentication/runtime failures are surfaced to the CLI as failure status `1`; usage/validation failures use status `2`. TUI history failures are translated into explicit loading/error states while server logout still runs.

Conversation/history HTTP errors remain typed by the existing `SamvaadApiException` taxonomy. In particular, 401 is authentication failure; 400/403/404 remain HTTP errors with their status codes; malformed JSON is a malformed-response error; transport failures are server-unavailable errors.

## Future realtime boundary

The existing server exposes WebSocket/STOMP contracts. When realtime is implemented, the authenticated access JWT will be used and the protocol implementation will remain behind the `realtime` boundary. The Phase 4 conversation store's distinction between server high-water mark and locally loaded sequence is intentionally compatible with a future realtime message stream.

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

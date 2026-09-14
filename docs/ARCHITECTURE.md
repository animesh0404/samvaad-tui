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

Phase 3 established the Lanterna UI boundary. Phase 4 replaced preview data with server-backed state. Phase 5 adds realtime transport and message sending. Phase 6 adds user lookup and pending friend-request workflows. Phase 7 adds an authoritative Friends tab and first-chat workflow:

```text
AppBootstrap
    |
    +--> AuthApiClient -------------> Samvaad Server
    |
    +--> ConversationApiClient ----> Samvaad Server
    |
    +--> UserLookupApiClient ------> Samvaad Server
    |
    +--> FriendRequestApiClient ---> Samvaad Server
    +--> FriendsApiClient ---------> Samvaad Server
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
      |\
      +-----> ConversationStore
      +-----> FriendRequestStore
      +-----> FriendStore
```

`TuiLauncher` is the bootstrap-facing seam. `TuiApp` owns terminal lifecycle, the render/input loop, and background work coordination. `TuiController` translates key strokes into state transitions. `TuiRenderer` renders only client-side display state. `TuiSession` provides the UI with display context, server-backed stores, history loading, realtime operations, and the token-free `FriendService` without exposing raw access/refresh tokens.

`ConversationApiClient` implements the verified conversation-list and message-history reads plus the verified first-message REST operation. There is no single-conversation lookup endpoint and no dedicated conversation-create endpoint; the client does not invent either.

`ConversationStore` preserves server-provided conversation ordering and message sequence ordering. It separates the server-provided `lastSequenceNumber` high-water mark from `highestLoadedSequence`, which records what the client has actually loaded locally. Realtime messages and authoritative REST first-message responses are merged by server-owned message identity and sequence.

`FriendRequestStore` is deliberately separate from `ConversationStore`. It holds the latest exact-username lookup result and pending incoming/outgoing requests, with independent loading/error state and exact server ordering.

`FriendStore` is a separate authoritative friends-list store. It is populated only by `GET /api/friends`, preserves the server's username-ascending order, and is never derived from friend requests, conversations, or messages.

When a friend is selected, `TuiApp` resolves an existing conversation by the friend's authoritative `userId`. If one is known, the existing conversation/history/realtime flow is reused. If none is known, the UI enters a pending-new-chat state. The first message is sent through `POST /api/conversations/direct/messages` using the friend's username and a fresh client-generated UUID `requestId`; the server creates the conversation when needed.

After a successful first-message request, the returned message and `conversationId` are authoritative. The TUI merges the persisted message, refreshes the conversation list, replaces the local list with server-provided ordering, selects the returned conversation, tops up its history, and then lets the existing realtime subscription machinery take over. The TUI never fabricates conversation metadata or message identity/sequence/timestamps.

History loading, user lookup, friend-request HTTP operations, Friends HTTP operations, and first-message HTTP operations are initiated from `TuiApp` on daemon workers so terminal input/rendering is not blocked. Realtime callbacks arrive on transport threads and update synchronized client state; the polling render loop repaints background changes while the UI is idle.

## HTTP architecture

`AuthApiClient`, `ConversationApiClient`, `UserLookupApiClient`, `FriendRequestApiClient`, and `FriendsApiClient` depend on `HttpTransport`. `JdkHttpTransport` implements that boundary using `java.net.http.HttpClient`. Jackson handles JSON serialization/deserialization, including Java `LocalDateTime` values returned by the server.

The conversation API client exposes the verified contracts:

```text
GET  /api/conversations/direct?limit&offset
GET  /api/conversations/direct/{conversationId}/messages?afterSequence&limit
POST /api/conversations/direct/messages
```

The first-message operation is addressed by exact friend username and carries `username`, `content`, and a client-generated UUID `requestId`. The server returns the authoritative persisted message, including the authoritative `conversationId`. HTTP 201 and 200 are successful outcomes under the existing server idempotency contract.

The Phase 6 social API clients expose:

```text
GET  /api/users/lookup?username={username}
POST /api/friend-requests
GET  /api/friend-requests/incoming
GET  /api/friend-requests/outgoing
POST /api/friend-requests/{requestId}/accept
POST /api/friend-requests/{requestId}/reject
POST /api/friend-requests/{requestId}/cancel
```

Phase 7 adds:

```text
GET /api/friends
```

`GET /api/friends` is authenticated, has no query parameters or body, returns safe `{userId, username}` friend records, returns `[]` for no friends, and provides deterministic username-ascending ordering. The TUI preserves that order and does not re-sort.

User lookup is exact-username lookup; the server performs case-insensitive trimmed matching and returns one safe user record. There is no prefix, substring, fuzzy, or paginated user search contract.

Friend-request request IDs and timestamps are server-owned. The client does not generate request IDs, idempotency keys, retries, or status transitions. Incoming and outgoing pending requests remain in server-provided newest-first order.

Conversation paging is offset-based. Message history uses the server's sequence cursor: only messages with `sequenceNumber > afterSequence` are returned, in ascending sequence order. Initial history uses `afterSequence=0&limit=20`.

The API layer preserves server ordering and does not sort by timestamps. Server timestamps are zone-less `LocalDateTime` values and are displayed without timezone conversion.

## Social-state architecture

```text
GET /api/users/lookup --------------------> UserLookupEntry
                                                |
POST/GET/mutate /api/friend-requests ------> FriendRequestStore
                                                |
GET /api/friends -------------------------> FriendStore
                                                |
                                                v
                                          TuiState / Renderer
```

`FriendService` is a token-free UI seam. `AppBootstrap` owns closures containing the authenticated access token and adapts API DTOs into UI model entries. The UI can request lookup, friend-request operations, friend-list refresh, and first-message sending without receiving a token.

The Friends tab is refreshed when entered and through explicit `g` refresh. There is no Friends polling and no friend realtime subscription in Phase 7.

Friendship is a domain relationship established by an accepted friend request, but the client consumes the dedicated server Friends API rather than reconstructing that relationship from request records. There is no client-side unfriend, block, friendship-status, or conversation creation-on-acceptance behavior.

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

The transport does not perform its own retries. `RealtimeManager` owns bounded reconnect policy. On an unexpected connection loss it reconnects with the same access token, restores the selected conversation subscription, and uses the HTTP history seam for catch-up from `highestLoadedSequence`.

Incoming broadcasts are mapped to `MessageEntry` and merged into `ConversationStore` using server-owned message ID and sequence. The store deduplicates messages and preserves ascending sequence order. The client never generates message IDs, sequence numbers, timestamps, or sender identity.

Normal subsequent sends remain non-optimistic. The client creates a fresh UUID `requestId`, sends the verified payload, and displays a pending `Sending...` state. The pending send becomes `Sent` only when an authoritative broadcast carrying the matching request ID is observed.

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
  -> token-free FriendService closure
  -> TuiSession
  -> fullscreen TUI
  -> selected conversation / Friends workflow
  -> history + realtime subscription / HTTP social operations
  -> exit
  -> realtime disconnect
  -> AuthApiClient
  -> POST /api/auth/logout
  -> local SessionState cleared
```

Realtime disconnect happens before HTTP logout. The same authenticated session/JWT is used for both protocols and for authenticated social HTTP operations.

## Session model

`SessionState` represents authenticated versus unauthenticated client state. `AuthSession` contains the server-issued access token, refresh token, session ID, expiry duration, acquisition timestamp, and authenticated user id derived from the JWT `sub` claim.

The client only decodes the JWT payload to obtain the server-defined user id for local sender attribution. It does not perform client-side JWT signature verification; authentication validity remains a server responsibility.

Authentication state and realtime credentials are currently in memory only. There is no local token database or credential store.

## Error handling

The API layer must avoid exposing credentials or sensitive response bodies. Authentication/runtime failures are surfaced to the CLI as failure status `1`; usage/validation failures use status `2`. TUI history and social-operation failures are translated into explicit loading/error states while server logout still runs.

Friend-request and Friends HTTP failures preserve the server status taxonomy at the API boundary without exposing response bodies. The Friends API maps 401 to authentication failure and other non-2xx responses to HTTP errors with the status preserved. First-message HTTP uses the same status-preserving boundary; its UI maps common 400/401/403/404/409 cases to concise status messages.

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
10. Reconcile client-visible send state only from authoritative server messages/responses.
11. Keep friendship state separate from conversation state.
12. Do not infer or invent social relationships when the server provides an authoritative Friends API.
13. Reuse the existing first-message conversation-creation contract rather than inventing a dedicated conversation-create API.

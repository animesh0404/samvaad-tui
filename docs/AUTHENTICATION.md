# Authentication

## Server contract

Authentication is provided by the existing Samvaad Server. The TUI consumes the contract; it does not implement server authentication rules.

Base path: `/api/auth`

Current endpoints:

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`

## Login

The login request contains `identifier`, `password`, `installationId`, `clientPlatform`, `clientName`, and `clientVersion`.

The TUI sends:

- `identifier` — CLI username
- `password` — securely collected password
- `installationId` — `null`
- `clientPlatform` — `TUI`
- `clientName` — `samvaad-tui`
- `clientVersion` — `0.1.0`

The TUI does not manufacture an installation identity.

## Login response

The server returns `accessToken`, `refreshToken`, `expiresIn`, and `sessionId`.

`AuthResponse` represents that response and `AuthSession` represents the authenticated session in memory.

The server-defined JWT contract has `sub = userId` and `sid = sessionId`. The client decodes the access-token payload's `sub` claim to obtain the authenticated user id for local sender attribution. The client does not perform JWT signature verification; authentication validity remains the server's responsibility.

## Session state

`SessionState` has authenticated and unauthenticated states. An authenticated state contains an `AuthSession`.

`AuthSession` contains:

- access token
- refresh token
- session ID
- authenticated user id derived from JWT `sub`
- expiry duration
- acquisition timestamp

The client can derive the expiry instant from acquisition time and `expiresIn`.

## Refresh

Refresh uses `POST /api/auth/refresh` with:

```json
{"refreshToken":"..."}
```

A successful response replaces the current authentication session with the latest server-issued values. There is no background refresh scheduler in the current implementation.

## Logout

Logout uses `POST /api/auth/logout` with `Authorization: Bearer <access-token>`.

After successful login, the TUI shell runs with display context only; raw access and refresh tokens remain in the session/bootstrap/realtime layers. Exiting the TUI disconnects realtime first, then bootstrap calls server logout and clears local authenticated state. Local cleanup is therefore not a substitute for server logout.

## Conversation, social HTTP, and realtime authentication

Phase 4 conversation and message-history requests use:

```text
Authorization: Bearer <access-token>
```

Phase 5 uses the **same access JWT from the same authenticated session** for STOMP CONNECT on the WebSocket connection at `/ws`.

Phase 6 user lookup and friend-request operations also use the same authenticated access JWT. Raw tokens remain in the bootstrap/API closure boundary and are not passed into TUI state or rendering code.

The realtime layer keeps the access token internally. `TuiSession`, `TuiApp`, `TuiController`, and `TuiRenderer` do not receive the raw token.

The TUI consumes only these verified conversation/history reads:

- `GET /api/conversations/direct?limit&offset`
- `GET /api/conversations/direct/{conversationId}/messages?afterSequence&limit`

Phase 6 adds these verified social reads/mutations:

- `GET /api/users/lookup?username={username}`
- `POST /api/friend-requests`
- `GET /api/friend-requests/incoming`
- `GET /api/friend-requests/outgoing`
- `POST /api/friend-requests/{requestId}/accept`
- `POST /api/friend-requests/{requestId}/reject`
- `POST /api/friend-requests/{requestId}/cancel`

Realtime destinations remain:

- WebSocket `/ws`
- STOMP send `/app/chat.send`
- STOMP subscription `/topic/conversations/{conversationId}`

## Security rules

- Passwords are collected as `char[]` where the console implementation permits and are cleared after use.
- Passwords and tokens must never be logged or printed.
- Error handling must not expose sensitive response bodies.
- Access and refresh tokens are held in memory only.
- Credentials and tokens are not persisted locally.
- The Lanterna UI must not receive or render authentication tokens.
- The realtime transport must not expose credentials through user-facing notices.
- Social API seams must expose model operations rather than raw access tokens.
- Decoding the JWT `sub` claim is only for local sender attribution; it is not an authentication decision.

## Current limitations

Authentication currently has no persistent credentials, persistent tokens, background refresh, multi-account storage, offline authentication, or device/installation identity generation. Phase 5 adds authenticated STOMP connectivity and realtime messaging; Phase 6 adds authenticated user lookup and friend-request operations without changing authentication semantics.

The current realtime error contract does not use a client-subscribed `/user/queue/errors` destination because that wiring was not established as part of the verified server contract. Transport ERROR/session callbacks are used instead.

The server does not currently expose an authoritative accepted-friends list, friendship-status endpoint, unfriend operation, or friend-request realtime contract. The TUI therefore does not invent those operations or infer accepted friends from conversations/pending requests.

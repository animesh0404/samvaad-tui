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

The server-defined JWT contract has `sub = userId` and `sid = sessionId`. Phase 4 decodes the access-token payload's `sub` claim to obtain the authenticated user id for local sender attribution. The client does not perform JWT signature verification; authentication validity remains the server's responsibility.

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

After successful login, the TUI shell runs with display context only; access and refresh tokens remain in the session/bootstrap layer. Exiting the TUI returns control to bootstrap, which calls server logout and then clears local authenticated state. Local cleanup is therefore not a substitute for server logout.

## Conversation/history HTTP authentication

Phase 4 conversation and message-history requests also use:

```text
Authorization: Bearer <access-token>
```

The same authenticated session/JWT is intended for future realtime use.

The TUI consumes only the verified server reads:

- `GET /api/conversations/direct?limit&offset`
- `GET /api/conversations/direct/{conversationId}/messages?afterSequence&limit`

The UI receives display context and client-side state, not the raw token.

## Security rules

- Passwords are collected as `char[]` where the console implementation permits and are cleared after use.
- Passwords and tokens must never be logged or printed.
- Error handling must not expose sensitive response bodies.
- Access and refresh tokens are held in memory only.
- Credentials and tokens are not persisted locally.
- The Lanterna UI must not receive or render authentication tokens.
- Decoding the JWT `sub` claim is only for local sender attribution; it is not an authentication decision.

## Current limitations

Authentication currently has no persistent credentials, persistent tokens, background refresh, multi-account storage, offline authentication, or device/installation identity generation. Phase 4 adds authenticated conversation/history reads and local sender attribution but does not add new authentication endpoints, token persistence, or realtime behavior.

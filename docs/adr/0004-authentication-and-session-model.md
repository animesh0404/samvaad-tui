# ADR 0004: Authentication and Session Model

- Status: Accepted
- Date: 2026-09-14

## Context

The Samvaad Server authenticates clients and creates server-side sessions. The TUI needs to retain the authentication response long enough to perform authenticated operations.

The server provides access and refresh tokens, expiry information, and a session ID.

## Decision

Represent client authentication state with `SessionState` and `AuthSession`.

`SessionState` represents authenticated versus unauthenticated client state. `AuthSession` contains the access token, refresh token, session ID, expiry duration, and acquisition timestamp.

Authentication state is held in memory only. Login, refresh, and logout are delegated to the server's existing authentication endpoints.

The TUI identifies itself with:

- `clientPlatform = TUI`
- `clientName = samvaad-tui`
- `clientVersion = 0.1.0`

The TUI does not manufacture an `installationId`.

## Consequences

### Positive

- client state directly reflects the server authentication contract
- no duplicated server session state
- straightforward authenticated HTTP handling
- no unnecessary credential persistence

### Negative

- restarting the TUI requires authentication again
- tokens exist in process memory while authenticated
- automatic/background refresh is not part of the current model

## Logout

Logout is server-authoritative. The client calls the server logout endpoint and then clears its local session state.

## Future changes

Persistent authentication, background refresh, multi-account support, or device identity require explicit requirements and a new decision where appropriate.

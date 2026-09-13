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
- `bootstrap` — startup orchestration, console prompting, authentication, and TUI lifecycle orchestration.
- `config` — application configuration and resolution.
- `auth` — authentication credential handling.
- `api` — HTTP transport, API clients, exceptions, and server DTOs.
- `session` — authenticated client session state.
- `realtime` — reserved for future WebSocket/STOMP integration.
- `model` — reserved for future client-side application state.
- `ui` — Lanterna terminal rendering, navigation, interaction, and terminal lifecycle.

The UI must not construct HTTP requests or STOMP frames directly.

## TUI shell architecture

Phase 3 introduces a deliberately small UI boundary:

```text
AppBootstrap
    |
    +--> AuthApiClient --> Samvaad Server
    |
    +--> TuiLauncher
             |
             v
          TuiApp
          /    \
 TuiController  TuiRenderer
      |             |
   TuiState     Lanterna Screen
      |
 PreviewInbox (temporary in-memory display data)
```

`TuiLauncher` is the bootstrap-facing seam. `TuiApp` owns terminal lifecycle and the render/input loop. `TuiController` translates key strokes into state transitions. `TuiRenderer` renders only client-side display state. The UI receives username/server display context, not access or refresh tokens.

The Phase 3 preview inbox is explicitly non-authoritative and exists only to exercise navigation and presentation before real conversation contracts are integrated.

The renderer authoritatively paints cells within its owned regions, including
interior spaces, so stale characters are not left behind when overlays close
or content changes. Help geometry is derived from content, padded, and safely
clamped to terminal dimensions. Resize events trigger Lanterna's normal
full-redraw path. Help input handling also accounts for terminal escape-sequence
edge cases so an unexpected merged key cannot strand the UI in the help state.

## Current HTTP architecture

`AuthApiClient` depends on `HttpTransport`. `JdkHttpTransport` implements that boundary using `java.net.http.HttpClient`. Jackson handles JSON serialization/deserialization.

This keeps protocol mechanics out of command/bootstrap code and provides a small seam for unit testing API behavior without a live server.

## Authentication/session flow

```text
CLI
  -> credentials
  -> AuthApiClient
  -> POST /api/auth/login
  <- accessToken + refreshToken + expiresIn + sessionId
  -> AuthSession
  -> SessionState(AUTHENTICATED)
  -> TuiLauncher
  -> fullscreen TUI
  -> exit
  -> AuthApiClient
  -> POST /api/auth/logout
  -> local SessionState cleared
```

Logout remains outside the TUI presentation layer and is server-authoritative.

## Session model

`SessionState` represents authenticated versus unauthenticated client state. `AuthSession` contains the server-issued access token, refresh token, session ID, expiry duration, and acquisition timestamp.

Authentication state is currently in memory only. There is no local token database or credential store.

## Error handling

The API layer must avoid exposing credentials or sensitive response bodies. Authentication/runtime failures are surfaced to the CLI as failure status `1`; usage/validation failures use status `2`. TUI failures are translated by bootstrap into a runtime failure while server logout still runs.

## Future realtime boundary

The existing server exposes WebSocket/STOMP contracts. When realtime is implemented, the authenticated access JWT will be used and the protocol implementation will remain behind the `realtime` boundary. No realtime implementation is part of the current TUI shell.

## Design principles

1. Server authority over client duplication.
2. Explicit boundaries over framework-heavy abstraction.
3. Small, intentional client-side state.
4. In-memory authentication state until a concrete persistence requirement exists.
5. Protocol details isolated inside API/realtime layers.
6. UI independent of transport implementation.
7. Implement only against verified server contracts.
8. Keep terminal lifecycle and presentation concerns inside the UI boundary.

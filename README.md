# Samvaad TUI

Thin Java terminal client for the existing Samvaad Server.

## Purpose

Provide a clean, keyboard-driven, WhatsApp-like terminal experience:
login, conversation list, direct conversations, message history, sending,
realtime receiving, user search, friend requests, logout, and shortcut help.

## Scope / boundary

Samvaad TUI is a **thin client**. The Samvaad Server is authoritative for
authentication, authorization, business rules, persistence, message identity,
sequencing, timestamps, idempotency, and domain behavior.

The client must not:

- redesign or duplicate server functionality,
- invent server contracts (endpoints, payloads, refresh-token behavior),
- manufacture an `installationId` or require one,
- generate message IDs, sequence numbers, timestamps, or sender identity.

Verified server contracts owned by the server project:

- JWT: `sub = userId`, `sid = sessionId`; `installationId` is optional metadata.
- Authenticated HTTP uses the access JWT.
- Realtime: WebSocket endpoint `/ws`, STOMP `Authorization: Bearer <access-jwt>`,
  send destination `/app/chat.send`, subscribe `/topic/conversations/{conversationId}`.
- Same session/JWT is used for HTTP and realtime.
- Logout uses the server session-revocation mechanism.

If a required contract has not been provided, implementation stops and asks
rather than guessing.

## Prerequisites

- Java 25 LTS (e.g. `openjdk 25.x`)
- No system Gradle required; use the checked-in wrapper (`./gradlew`)

## Build

```text
./gradlew build
```

## Run

```text
./gradlew run --args="--server http://localhost:8080 --username alice"
```

Expected invocation once installed:

```text
samvaad-tui --server http://localhost:8080 --username alice
```

Behavior:

- Missing `--server` → prompt for server URL (must start with `http://`/`https://`).
- Missing `--username` → prompt for username.
- Always prompt securely for password (`System.console().readPassword()` when
  available; visible-input fallback with a warning when no console exists).
- Password is held as `char[]`, never printed, and cleared after use.
- After successful authentication, the client enters the full-screen Lanterna TUI.
- Exiting the TUI logs out through the server and clears local session state.

Other useful commands:

```text
./gradlew test
./gradlew run --args="--help"
./gradlew installDist   # lays out build/install/samvaad-tui/bin/samvaad-tui
```

Packaging: `build/libs/*.jar` declares `Main-Class: com.samvaad.tui.Main`
but is a thin JAR (runtime dependencies stay external — no fat JAR).
The Gradle application distribution (`installDist`/`distZip`/`distTar`)
is the primary V1 distribution mechanism.

## Phase 3 TUI shell

Phase 3 adds the Lanterna-based full-screen terminal shell only. It currently
uses clearly marked in-memory preview conversations so the UI can be exercised
before conversation APIs are integrated.

Current shell capabilities:

- full-screen alternate-terminal UI with header, conversation sidebar, chat panel, composer, and status line;
- Up/Down or `k`/`j` conversation navigation;
- `Tab` focus switching;
- `Enter` selection/composer interaction (message sending remains future work);
- `F1` or `?` help overlay;
- `Esc` closes help/unfocuses the composer;
- `F10`, `Ctrl+C`, or `q` from the conversation list exits;
- terminal cleanup/restoration on normal and Ctrl+C exits;
- authenticated server logout remains outside the UI and is performed by bootstrap.

Phase 3 does **not** implement conversation APIs, message history, sending,
realtime, friend requests, or user search.

## CLI usage

```text
samvaad-tui [--server URL] [--username USERNAME]
samvaad-tui --help
samvaad-tui --version
```

Exit codes: `0` success, `1` authentication/server/runtime failure, `2` usage/validation error.

## Authentication contract (verified, server-authoritative)

Base API URL is the configured server URL. The client implements exactly
these endpoints and no others:

* Login: `POST /api/auth/login`
  ```json
  {
    "identifier": "<username>",
    "password": "<password>",
    "installationId": null,
    "clientPlatform": "TUI",
    "clientName": "samvaad-tui",
    "clientVersion": "0.1.0"
  }
  ```
  `installationId` is optional server metadata — this client always sends
  null and never generates one. `identifier` is the username from the CLI.
* Login response: `{ "accessToken", "refreshToken", "expiresIn", "sessionId" }`.
* Refresh: `POST /api/auth/refresh` with `{ "refreshToken" }`; response has
  the same shape as login. The client replaces its tokens with the latest
  server-issued pair; there is no background refresh yet.
* Logout: `POST /api/auth/logout` with `Authorization: Bearer <accessToken>`;
  the server revokes the persisted session. Logout is always a server call,
  never just local cleanup.

JWT/session facts: `sub` = userId, `sid` = sessionId; the same session is
used for HTTP and realtime. The client never persists credentials or tokens
to disk; passwords are `char[]` cleared after the request; tokens live in
memory only and are never logged or printed.

## Architecture

Simple, explicit packages under `com.samvaad.tui`:

```text
cli/          application startup and command-line handling (picocli)
bootstrap/    startup orchestration + console prompting
api/          HTTP communication and server DTOs
auth/         authentication/session credential holders
session/      authenticated-session state for the app lifetime
realtime/     WebSocket/STOMP communication (future)
model/        client-side state (future)
ui/           Lanterna rendering/navigation/interaction
config/       application configuration (AppConfig + resolver)
```

Rules:

- The UI must not directly construct HTTP requests or STOMP frames.
- Server DTOs stay separate from UI state where useful.
- Prefer immutable records for DTOs.
- No ORM, database, SQLite, embedded server, broker, reactive framework,
  caching, or offline sync without a concrete requirement.

## Current implementation status

**Phase 1 — Foundation: done** (commit `57c51cd`).

**Phase 2 — Authentication and session management: done** (commit `241e285`).

**Phase 3 — TUI shell: done** (commit `595b355`).

Implemented in Phase 3:

- Lanterna `3.1.5`.
- Full-screen terminal lifecycle and restoration.
- Sidebar/chat/header/composer/status layout.
- Keyboard navigation and help overlay.
- In-memory preview inbox, explicitly marked as preview data.
- TUI receives display data only; authentication tokens remain outside the UI.
- Logout/revocation remains server-authoritative.
- 60 automated tests passing at phase completion.

Next phases will be defined incrementally after the relevant Samvaad Server
contracts are verified. Expected areas include conversation list/history,
direct conversations, messaging, realtime receiving, username search, and
friend/request workflows.

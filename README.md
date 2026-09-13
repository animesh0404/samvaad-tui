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

Other useful commands:

```text
./gradlew test
./gradlew run --args="--help"
./gradlew installDist   # lays out build/install/samvaad-tui/bin/samvaad-tui
```

## CLI usage

```text
samvaad-tui [--server URL] [--username USERNAME]
samvaad-tui --help
samvaad-tui --version
```

Exit codes: `0` success, `2` usage/validation error.

## Architecture

Simple, explicit packages under `com.samvaad.tui`:

```text
cli/          application startup and command-line handling (picocli)
bootstrap/    startup orchestration + console prompting
auth/         authentication/session credential holders
session/      authenticated-session state for the app lifetime
api/          HTTP communication and server DTOs (reserved, Phase 2+)
realtime/     WebSocket/STOMP communication (reserved, Phase 5)
model/        client-side state (reserved, Phase 3+)
ui/           Lanterna rendering/navigation/interaction (reserved, Phase 3)
config/       application configuration (AppConfig + resolver)
```

Responsibilities:

- `cli/bootstrap` → startup and command-line handling.
- `auth/session` → authentication/session state.
- `api` → HTTP communication and server DTOs.
- `realtime` → WebSocket/STOMP communication.
- `model` → client-side state.
- `ui` → rendering, navigation, user interaction.
- `config` → application configuration.

Rules:

- The UI must not directly construct HTTP requests or STOMP frames.
- Server DTOs stay separate from UI state where useful.
- Prefer immutable records for DTOs.
- No ORM, database, SQLite, embedded server, broker, reactive framework,
  caching, or offline sync.

## Current implementation status

**Phase 1 — Foundation: done.**

- Java 25 + Gradle (Groovy DSL) + wrapper `9.7.1`.
- Entry point `com.samvaad.tui.Main`, picocli command, `--server`/`--username`.
- Interactive prompts for missing server/username; secure password prompt
  with no-console fallback; password cleared from memory.
- `AppConfig` / `AppConfigResolver` validation and normalization.
- `Credentials` (`char[]` + `clear()`), `SessionState` (unauthenticated).
- `api/realtime/model/ui` exist as documented placeholders only.
- Unit tests (JUnit) for CLI parsing, config resolution, prompting,
  bootstrap flow, credentials clearing, session state.
- **No Samvaad Server calls, no authentication, no TUI yet** (by design).

Planned next phases (not started):

- Phase 2 — Authentication (needs verified auth contract).
- Phase 3 — TUI shell (Lanterna).
- Phase 4 — Conversations.
- Phase 5 — Messaging/realtime.
- Phase 6 — Friend workflows.
- Phase 7 — Logout/polish.

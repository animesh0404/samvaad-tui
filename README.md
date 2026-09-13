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

Packaging: `build/libs/*.jar` declares `Main-Class: com.samvaad.tui.Main`
but is a thin JAR (runtime dependencies stay external — no fat JAR).
The Gradle application distribution (`installDist`/`distZip`/`distTar`)
is the primary V1 distribution mechanism.

With a server running, a full Phase-2 run looks like:

```text
./gradlew run --args="--server http://localhost:8080 --username alice"
# prompts for password, logs in, prints a sanitized summary,
# revokes the server session via logout, clears local secrets, exits 0
```

## CLI usage

```text
samvaad-tui [--server URL] [--username USERNAME]
samvaad-tui --help
samvaad-tui --version
```

Exit codes: `0` success, `1` authentication/server failure, `2` usage/validation error.

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
memory only and are never logged or printed (only server, username,
session id, and expiry appear in output).

## Architecture

Simple, explicit packages under `com.samvaad.tui`:

```text
cli/          application startup and command-line handling (picocli)
bootstrap/    startup orchestration + console prompting
auth/         authentication/session credential holders
session/      authenticated-session state for the app lifetime
api/          HTTP communication and server DTOs
realtime/     WebSocket/STOMP communication (reserved for future implementation)
model/        client-side state (reserved for future implementation)
ui/           Lanterna rendering/navigation/interaction (reserved for future implementation)
config/       application configuration (AppConfig + resolver)
```

Responsibilities:

- `cli/bootstrap` → startup and command-line handling.
- `auth/session` → authentication/session state.
- `api` → HTTP communication and server DTOs.
- `realtime` → future WebSocket/STOMP communication.
- `model` → future client-side application state.
- `ui` → future terminal rendering, navigation, and interaction.
- `config` → application configuration.

Rules:

- The UI must not directly construct HTTP requests or STOMP frames.
- Server DTOs stay separate from UI state where useful.
- Prefer immutable records for DTOs.
- No ORM, database, SQLite, embedded server, broker, reactive framework,
  caching, or offline sync.

## Current implementation status

**Phase 1 — Foundation: done** (commit `57c51cd`).

**Phase 2 — Authentication and session management: done** (commit `241e2856b3a87a8ae3d1bf1ce51eae4dde2fd0c6`).

Implemented in Phase 2:

- Jackson `2.22.2` for JSON.
- JDK `java.net.http.HttpClient` for HTTP.
- `AuthApiClient` implementing the verified server login, refresh, and logout contracts.
- `HttpTransport` abstraction with `JdkHttpTransport`.
- Authentication DTO records.
- `SamvaadApiException` for API/transport/authentication failures.
- `SessionState` and `AuthSession` for in-memory authenticated session state.
- Access token, refresh token, session ID, expiry, and acquisition timestamp handling.
- Token replacement after refresh.
- TUI client identity: `clientPlatform=TUI`, `clientName=samvaad-tui`, `clientVersion=0.1.0`.
- `installationId` is sent as `null`; the client does not manufacture an installation identity.
- CLI login → authenticated session → sanitized summary → server logout/revocation → local cleanup.
- Authentication failure exits with status `1`.
- Usage/validation failures exit with status `2`.
- Authentication tests use a mocked HTTP transport.
- No local credential/token persistence.
- No TUI, conversations, messaging, realtime, friends, or search has been implemented yet.

### Next

**Phase 3 — TUI shell:** Lanterna-based full-screen terminal UI.

Future phases will be defined incrementally after the relevant server contracts are verified. Expected areas include:

- conversation list/history
- direct conversations
- messaging
- realtime receiving
- username search
- friend/request workflows
- logout/polish

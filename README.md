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

For the fullscreen TUI, use the installed application distribution:

```text
./gradlew installDist
build/install/samvaad-tui/bin/samvaad-tui --server http://localhost:8080 --username alice
```

The Gradle `run` task is useful for CLI/non-interactive behavior, but the
fullscreen Lanterna TUI requires the installed launcher with a real terminal.

Behavior:

- Missing `--server` → prompt for server URL (must start with `http://`/`https://`).
- Missing `--username` → prompt for username.
- Always prompt securely for password (`System.console().readPassword()` when
  available; visible-input fallback with a warning when no console exists).
- Password is held as `char[]`, never printed, and cleared after use.
- After successful authentication, the client loads the server conversation list
  and enters the full-screen Lanterna TUI.
- Selecting a conversation lazily loads its initial message history on a worker
  thread so HTTP does not block terminal input/rendering.
- Realtime is connected with the same authenticated access JWT and follows the
  selected conversation subscription.
- User lookup and friend-request operations run through token-free UI seams on
  background workers; pending request lists refresh explicitly after mutations
  and periodically while the request view is active.
- Exiting the TUI disconnects realtime first, then performs server logout and
  clears local session state.

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

Phase 3 established the Lanterna-based full-screen terminal shell and its
keyboard/rendering behavior.

Key capabilities retained:

- full-screen alternate-terminal UI with header, conversation sidebar, chat panel, composer, and status line;
- Up/Down or `k`/`j` conversation navigation;
- navigation does not wrap at the list boundaries;
- `Tab` focus switching with visible active-pane indication;
- `Enter` selection/composer interaction;
- `F1` or `?` help overlay;
- `Esc` closes help/unfocuses the composer;
- `F10`, `Ctrl+C`, or `q` from the conversation list exits;
- terminal cleanup/restoration on normal and Ctrl+C exits;
- Help overlay handles rapid/merged terminal key input without stranding the UI;
- Help overlay redraws cleanly and derives its width from content with terminal-size clamping and padding.

Phase 3 implementation commit: `595b355`; subsequent fixes: `42e7f29`.

## Phase 4 — server-backed conversations and history

Phase 4 replaced the Phase 3 preview inbox with real server-backed conversation
and message-history data.

Implemented:

- `GET /api/conversations/direct?limit&offset` through `ConversationApiClient`;
- `GET /api/conversations/direct/{conversationId}/messages?afterSequence&limit`;
- Jackson Java-time support for server `LocalDateTime` values, with no timezone conversion;
- server DTOs kept separate from UI state;
- JWT `sub` decoded to the authenticated `userId` for own-message attribution;
- conversation state preserves the exact server-provided ordering;
- conversation `lastSequenceNumber` is kept separate from the highest message sequence actually loaded locally;
- initial history loads with `afterSequence=0` and `limit=20`;
- history loads happen on daemon worker threads rather than blocking the UI loop;
- loading, empty, and error states for message history;
- nullable `otherParticipantUsername` has a client-side display fallback;
- Phase 3 preview conversation/message classes removed.

Phase 4 implementation commit: `9393514`.

## Phase 5 — message sending and realtime

Phase 5 adds server-backed message sending and realtime message delivery.

Implemented:

- Spring `WebSocketStompClient` behind a small `realtime` transport seam;
- WebSocket URL derived from the configured HTTP(S) server URL to `/ws`;
- STOMP CONNECT with `Authorization: Bearer <access-token>`;
- per-conversation subscription at `/topic/conversations/{conversationId}`;
- message send at `/app/chat.send`;
- client-generated UUID `requestId` for send correlation/idempotency, with message ID, sequence, and timestamp remaining server-owned;
- authoritative realtime broadcasts merged into `ConversationStore` by server message identity and sequence;
- no optimistic message persistence/rendering;
- pending sends transition from `Sending...` to `Sent` only when the matching authoritative broadcast is observed;
- server-provided timestamps displayed for sent and received messages;
- visible Tab focus between conversation and chat/composer panes;
- bounded reconnect with the same access token, resubscription, and HTTP history catch-up from `highestLoadedSequence`;
- realtime disconnect before the existing HTTP logout/revocation call;
- background changes repaint while the UI is idle through the polling render loop;
- realtime transport errors remain token-free and user-safe.

The server remains authoritative: the TUI does not generate message IDs,
sequence numbers, timestamps, sender identity, or retry requests. There is
no client-side optimistic message confirmation.

The server's `/user/queue/errors` destination is intentionally not subscribed
because that wiring was not established as part of the verified contract.
Transport ERROR frames/session callbacks are surfaced instead. A silently
dropped send therefore remains pending rather than being falsely marked sent.

Phase 5 implementation commit: `97d01c9`.

## Phase 6 — user lookup and friend requests

Phase 6 adds the first social workflow while keeping the TUI strictly within
verified Samvaad Server contracts.

Implemented:

- exact username lookup through `GET /api/users/lookup?username={username}`;
- URL encoding and local blank-input validation;
- `POST /api/friend-requests` to send a request by exact username;
- pending incoming/outgoing request reads;
- recipient-only accept/reject and sender-only cancel operations;
- server-owned request IDs, timestamps, statuses, authorization, and duplicate handling;
- a dedicated `FriendRequestStore`, separate from `ConversationStore`;
- token-free `FriendService` seam with authenticated closures owned by `AppBootstrap`;
- background HTTP work so lookup/request operations do not block terminal input/rendering;
- explicit refresh after mutations and 30-second refresh while the request view is active;
- search and request-list TUI workflows with keyboard help and action feedback;
- no fuzzy/prefix search, friend list, unfriend/status endpoint, friend-request realtime, or conversation creation invented on the client.

The server currently does **not** expose an authoritative accepted-friends
list. Pending incoming/outgoing requests cannot be used as a friends list
because accepted requests leave those pending collections. Likewise, accepting
a friend request does not create a conversation. A future Friends sidebar and
first-chat workflow therefore require the relevant server contracts first.

Phase 6 implementation commit: `fcb3ac3`.

Phase 6 verification included `./gradlew clean test` with **195 tests passing**
and `./gradlew installDist` succeeding. Manual verification exercised lookup,
friend-request send/accept and the existing messaging flow. Interactive smoke
verification is a manual responsibility rather than an automated PTY harness.

## CLI usage

```text
samvaad-tui [--server URL] [--username USERNAME]
samvaad-tui --help
samvaad-tui --version
```

Exit codes: `0` success, `1` authentication/server/runtime failure, `2` usage/validation error.

## Authentication contract (verified, server-authoritative)

Base API URL is the configured server URL. The client implements exactly
the verified endpoints described in `docs/AUTHENTICATION.md`; Phase 6 social
HTTP uses the same access JWT as conversation/history HTTP.

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
to disk; passwords are `char[]` cleared after use; tokens live in memory only
and are never logged or printed.

## Architecture

Simple, explicit packages under `com.samvaad.tui`:

```text
cli/          application startup and command-line handling (picocli)
bootstrap/    startup orchestration + console prompting + lifecycle orchestration
api/          HTTP transport, API clients, server DTOs, API exceptions
auth/         authentication credential holders + JWT subject decoding
session/      authenticated-session state for the app lifetime
realtime/     WebSocket/STOMP transport + realtime lifecycle management
model/        server-backed conversation/message + social presentation state
ui/           Lanterna rendering/navigation/interaction and token-free service seams
config/       application configuration (AppConfig + resolver)
```

Rules:

- The UI must not directly construct HTTP requests or STOMP frames.
- Server DTOs stay separate from UI state.
- Prefer immutable records for DTOs.
- The conversation store preserves server ordering; it does not use timestamps for ordering.
- Server `lastSequenceNumber` is a high-water mark, not proof that all messages through that sequence are locally loaded.
- Realtime reconnect catch-up uses the locally loaded sequence as the HTTP `afterSequence` cursor.
- Friend-request state remains separate from conversation/message state.
- No ORM, database, SQLite, embedded server, broker, reactive framework,
  caching, or offline sync without a concrete requirement.

## Current implementation status

**Phase 1 — Foundation: done** (commit `57c51cd`).

**Phase 2 — Authentication and session management: done** (commit `241e285`).

**Phase 3 — TUI shell: done** (implementation commit `595b355`; subsequent
Phase 3 fixes `42e7f29`).

**Phase 4 — Server-backed conversations and message history: done**
(commit `9393514`).

**Phase 5 — Message sending and realtime: done**
(commit `97d01c9`).

**Phase 6 — User lookup and friend requests: done**
(commit `fcb3ac3`).

Phase 6 leaves the client intentionally unable to display an accepted-friends
list or initiate a first conversation from a friend entry because those
server contracts are not currently exposed. The next phase must begin with
server-contract verification rather than client-side invention.

For detailed architecture, authentication, development guidance, and decision
records, see `docs/ARCHITECTURE.md`, `docs/AUTHENTICATION.md`,
`docs/DEVELOPMENT.md`, and `docs/adr/`.

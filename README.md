# Samvaad TUI

Thin Java terminal client for the existing Samvaad Server.

## Purpose

Provide a clean, keyboard-driven terminal experience for login, direct conversations, message history, realtime messaging, exact username lookup, friend requests, Friends, starting chats from Friends, and logout.

## Scope / boundary

Samvaad TUI is a **thin client**. The Samvaad Server remains authoritative for authentication, authorization, business rules, persistence, message identity, conversation identity, sequencing, timestamps, idempotency, friendship, and domain behavior.

The TUI does not invent endpoints or server-owned identifiers, does not persist credentials/tokens to disk, and does not add a database, embedded server, broker, or offline-sync layer.

Verified server/realtime contracts include authenticated HTTP, `GET /api/conversations/direct?limit&offset`, message history, the direct first-message REST operation, exact user lookup, friend-request operations, `GET /api/friends`, and WebSocket `/ws` with STOMP `/app/chat.send` and `/topic/conversations/{conversationId}`.

## Prerequisites

- Java 25 LTS
- Git
- checked-in Gradle Wrapper (`./gradlew`)

## Build / run

```text
./gradlew build
./gradlew test
./gradlew installDist
build/install/samvaad-tui/bin/samvaad-tui --server http://localhost:8080 --username alice
```

The fullscreen Lanterna TUI requires a real terminal. `./gradlew run --args="--help"` is useful for CLI/non-interactive behavior.

Password input uses `System.console().readPassword()` when available, with a visible-input warning fallback when no console exists. Passwords are held as `char[]` and cleared after use.

## Current behavior

After authentication the client loads the authoritative conversation list, establishes realtime using the same access JWT, and enters the fullscreen TUI. Conversation history loads lazily on daemon workers. Subsequent messages use the existing STOMP realtime path and are reconciled from authoritative server broadcasts.

### Friends and chat creation

The Friends tab uses the authoritative `GET /api/friends` response and preserves server ordering. Selecting a friend resolves a known conversation by `userId`. If no known conversation exists, the TUI enters a pending-new-chat state and sends the first message through the existing `POST /api/conversations/direct/messages` contract with `username`, `content`, and a fresh UUID `requestId`. The server-created conversation/message remain authoritative.

### Automatic conversation discovery

Phase 7C adds an authoritative conversation-list refresh every **5 seconds** while the fullscreen TUI is active. It reuses `ConversationListLoader` and runs on a guarded daemon worker, so HTTP never blocks rendering/input and overlapping refreshes are prevented.

This solves the cross-client discovery case where one user creates a new conversation while the other user's TUI does not yet know the conversation ID. Polling discovers the conversation; it does **not** replace STOMP message delivery.

Successful list reconciliation preserves the selected conversation by `conversationId`, follows it across server-side reordering, and never automatically opens a newly discovered conversation or steals focus. Background refresh failures preserve the visible valid list.

### Universal manual refresh

**F5** is the universal manual refresh key. It is handled centrally and delegates to the existing refresh mechanism for the active server-backed view:

- Conversations → authoritative conversation-list refresh
- Friends → authoritative Friends refresh
- Incoming/outgoing friend requests → existing request refresh
- Search → existing exact-username lookup action

F5 is asynchronous, does not restart the TUI, and does not steal selection/focus. Existing `g` refresh behavior remains available in Friends and request views.

## Keyboard shortcuts

```text
Up / Down / k / j  select active sidebar item
Left / Right       switch CONVERSATIONS / FRIENDS when sidebar is focused
Tab                switch focus / request section
Enter              open selected item / send / select friend / lookup action
F5                 refresh authoritative data for active server-backed view
/                  exact username search
g                  existing Friends/request refresh shortcut where supported
r                  friend requests
F1 / ?              help
Esc                 close help / leave social mode / cancel pending new chat
F10 / Ctrl+C        quit
q                  quit from conversation/friends list
```

## Architecture

```text
cli/          picocli startup and command handling
bootstrap/    startup/authentication/lifecycle orchestration
api/          HTTP transport, API clients, DTOs, exceptions
auth/         authentication credential handling and JWT subject decoding
session/      authenticated in-memory session state
realtime/     WebSocket/STOMP transport and lifecycle
model/        server-backed conversation/message/social presentation state
ui/           Lanterna rendering, navigation, interaction, refresh orchestration
config/       application configuration
```

The UI does not construct HTTP requests or STOMP frames directly. `TuiApp` owns terminal lifecycle, the render/input loop, and background workers. `TuiController` owns key-to-action mapping, including central F5 refresh dispatch. `ConversationStore`, `FriendRequestStore`, and `FriendStore` remain separate state boundaries.

## Implementation status

- Phase 1 — Foundation: **done**
- Phase 2 — Authentication/session management: **done**
- Phase 3 — TUI shell: **done**
- Phase 4 — Server-backed conversations/history: **done**
- Phase 5 — Message sending/realtime: **done**
- Phase 6 — User lookup/friend requests: **done**
- Phase 7A — Server Friends API: **done**
- Phase 7B — Friends tab/start chat: **done**
- Phase 7C — Automatic conversation discovery + universal F5 refresh: **done** (`72968725da0046c445598759c6fd613169d27bb7`)

Phase 7C verification: **271 tests passing**, zero failures/errors/skips, and `./gradlew installDist` succeeds.

## Documentation

See `docs/ARCHITECTURE.md`, `docs/AUTHENTICATION.md`, `docs/DEVELOPMENT.md`, and `docs/adr/` for detailed architecture, verified authentication/server contracts, development guidance, and decision records. ADR-0011 records the Phase 7C automatic discovery and universal refresh decision.

Interactive fullscreen smoke testing is a manual real-terminal responsibility; the project deliberately does not use an automated PTY harness.

## Authentication

Login is `POST /api/auth/login` with `clientPlatform: TUI`; the server returns access/refresh tokens, expiry, and session ID. The client uses the access JWT for authenticated HTTP and realtime. `installationId` is optional metadata and is not manufactured by the TUI. Logout calls the server revocation endpoint before local session cleanup.

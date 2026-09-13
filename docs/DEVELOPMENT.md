# Development Guide

## Toolchain

- Java 25 LTS
- Gradle via the checked-in Gradle Wrapper
- Git

No system Gradle installation is required.

## Build and test

```bash
./gradlew build
./gradlew test
```

For the fullscreen TUI, build the installed application distribution:

```bash
./gradlew installDist
build/install/samvaad-tui/bin/samvaad-tui --server http://localhost:8080 --username alice
```

The fullscreen Lanterna TUI requires a real terminal. The installed
application launcher is the supported manual smoke-test path; `./gradlew run`
is useful for CLI/non-interactive behavior but is not the primary way to run
the fullscreen shell.

Show CLI help:

```bash
./gradlew run --args="--help"
```

## Packaging

The `jar` task declares `Main-Class: com.samvaad.tui.Main`, so the built
JAR carries a correct executable entry point. It is a thin JAR: runtime
dependencies (picocli, Jackson, Lanterna) remain external, so plain
`java -jar` is not standalone.

The primary V1 distribution mechanism is the Gradle application
distribution (`installDist`, `distZip`, `distTar`), which bundles the JAR,
all runtime dependencies, and the `samvaad-tui` launcher. No fat/uber JAR
(e.g. Shadow) is used.

## TUI development

Phase 3 established the Lanterna `3.1.5` fullscreen shell. Phase 4 keeps the
same UI boundary but replaces preview data with server-backed conversation and
message state.

Responsibilities:

- `TuiApp` owns terminal lifecycle, render/input loop, and lazy history-load worker creation.
- `TuiController` owns keyboard-to-state transitions.
- `TuiRenderer` owns terminal presentation.
- `TuiLauncher` is the bootstrap seam.
- `TuiSession` is a token-free bundle of UI display context, conversation state, and history-loading behavior.
- `ConversationStore` owns in-memory server-backed conversation/message presentation state and preserves server ordering.
- `ConversationApiClient` owns the verified conversation/history HTTP reads.
- `MessageHistoryLoader` keeps the UI's history-loading operation behind a small seam for testing.

Current bindings:

```text
Up / Down / k / j  select conversation
Tab                 switch focus
Enter               open selected item / composer notice
F1 / ?              help
Esc                 close help / return focus to conversations
F10 / Ctrl+C        quit
q (conversation list) quit
```

Navigation does not wrap at list boundaries. Help is an overlay, but terminal
escape-sequence edge cases are handled so an unexpected merged key cannot
strand the UI in the help state. The renderer paints owned cells explicitly,
clears stale interior characters through normal frame rendering, handles
resize-triggered full redraws, and derives Help geometry from content with
symmetric padding and narrow-terminal clamping.

Phase 4 conversation/history behavior:

- conversation list comes from `GET /api/conversations/direct?limit&offset`;
- server-provided conversation order is preserved verbatim;
- selecting a conversation triggers initial history loading with `afterSequence=0&limit=20`;
- history HTTP runs on a daemon worker so the UI thread remains responsive;
- messages are displayed in server-provided ascending sequence order;
- `lastSequenceNumber` is retained as the server high-water mark while `highestLoadedSequence` tracks locally loaded messages separately;
- no load-more UI is implemented yet;
- loading, empty, and error states are rendered explicitly;
- no message sending, realtime, friends/search, or friend-request APIs are called in Phase 4.

## CLI behavior

Usage:

```text
samvaad-tui [--server URL] [--username USERNAME]
```

If server URL or username is absent, the client prompts for it. The password is collected securely through the console when available; the non-console fallback warns that input may be visible.

Current flow:

1. resolve server URL
2. resolve username
3. collect password
4. call login
5. establish in-memory authenticated session and user id from JWT `sub`
6. load the server conversation list
7. enter the fullscreen TUI shell
8. lazily load selected conversation history
9. exit the TUI
10. call server logout
11. clear local session state
12. exit

Exit codes:

- `0` — successful execution
- `1` — authentication/server/runtime failure
- `2` — usage/validation failure

## Testing approach

API tests use the `HttpTransport` seam and a fake transport rather than requiring a running server. Conversation API tests verify exact paths/query parameters, JSON parsing including nullable participant usernames and `LocalDateTime`, malformed responses, transport failures, and 400/401/403/404 mappings. Session tests cover authentication state, token replacement, and authenticated user-id handling. Model tests cover server-order preservation and the distinction between server high-water marks and locally loaded sequence. TUI state/controller tests exercise keyboard bindings and history-loading transitions, and renderer tests use a virtual terminal where practical.

A live server smoke test can be used for end-to-end authentication, server-backed conversation/history loading, logout/revocation, and terminal lifecycle verification. Use disposable test data and never commit credentials or tokens.

The current Phase 4 baseline has **106 automated tests passing**. The Phase 3 renderer/input regressions remain covered as part of that suite.

## Dependency policy

Keep the client dependency footprint small. Current primary runtime dependencies are picocli, Jackson (including `jackson-datatype-jsr310` for server `LocalDateTime`), and Lanterna. JUnit is test-only. Do not introduce a server framework or persistence technology to solve a client concern without a concrete requirement.

WebSocket/STOMP dependencies belong to a future implementation phase and should be introduced only when realtime capability is actually implemented.

## Implementation workflow

For each phase:

1. establish scope
2. verify relevant Samvaad Server contracts
3. implement the client behavior
4. run tests/build
5. inspect the actual implementation
6. perform manual smoke verification where applicable
7. reconcile documentation
8. update the root `README.md` as part of the same documentation gate
9. commit and push
10. verify the repository state

The root README is part of the primary project documentation, not a separate afterthought.

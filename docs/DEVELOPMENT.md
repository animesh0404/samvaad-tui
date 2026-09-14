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
dependencies remain external, so plain `java -jar` is not standalone.

The primary V1 distribution mechanism is the Gradle application
distribution (`installDist`, `distZip`, `distTar`), which bundles the JAR,
all runtime dependencies, and the `samvaad-tui` launcher. No fat/uber JAR
(e.g. Shadow) is used.

## TUI development

Phase 3 established the Lanterna `3.1.5` fullscreen shell. Phase 4 replaced
preview data with server-backed conversation/message state. Phase 5 adds
message sending and realtime delivery.

Responsibilities:

- `TuiApp` owns terminal lifecycle, the polling render/input loop, and background worker coordination.
- `TuiController` owns keyboard-to-state transitions and composer/send interaction.
- `TuiRenderer` owns terminal presentation, including active-pane focus, message timestamps, and send status.
- `TuiLauncher` is the bootstrap seam.
- `TuiSession` is a token-free bundle of UI display context, conversation state, history-loading behavior, and realtime operations.
- `ConversationStore` owns in-memory server-backed conversation/message presentation state, preserves server ordering, deduplicates authoritative messages, and tracks high-water versus locally loaded sequence.
- `ConversationApiClient` owns the verified conversation/history HTTP reads.
- `MessageHistoryLoader` keeps history loading behind a small seam for testing and realtime catch-up.
- `RealtimeClient` is the transport seam.
- `SpringRealtimeClient` implements the verified WebSocket/STOMP contract.
- `RealtimeManager` owns connection lifecycle, conversation subscriptions, sending, bounded reconnect, catch-up, and token containment.

Current bindings:

```text
Up / Down / k / j  select conversation
Tab                 switch focus
Enter               open selected item / composer or send message
F1 / ?              help
Esc                 close help / return focus to conversations
F10 / Ctrl+C        quit
q (conversation list) quit
```

The active pane is visibly indicated. Messages display server-provided
`LocalDateTime` values without timezone conversion. A send remains `Sending...`
until the authoritative realtime broadcast carrying the matching request ID
is observed, then becomes `Sent`.

The renderer paints owned cells explicitly, handles resize-triggered redraws,
and derives Help geometry from content with padding and narrow-terminal
clamping. The polling render loop ensures background history/realtime changes
become visible while the user is idle.

## HTTP and realtime behavior

The conversation/history HTTP paths remain:

```text
GET /api/conversations/direct?limit&offset
GET /api/conversations/direct/{conversationId}/messages?afterSequence&limit
```

Phase 5 realtime paths are:

```text
WebSocket /ws
STOMP SEND /app/chat.send
STOMP SUBSCRIBE /topic/conversations/{conversationId}
```

STOMP CONNECT uses `Authorization: Bearer <access-token>`. The same access JWT
used for HTTP is used for realtime. `SpringRealtimeClient` is a client-side
library adapter only; the TUI does not become a Spring Boot application and
does not host a server.

The send payload supplies conversation ID, message content, and a fresh UUID
`requestId`. Message ID, sequence number, timestamp, and sender identity remain
server-owned. The client does not retry sends and does not optimistically mark
messages as persisted.

On unexpected realtime loss, `RealtimeManager` performs bounded reconnect,
resubscribes to the selected conversation, and requests history after the
store's `highestLoadedSequence`. The transport itself does not retry.

The server's `/user/queue/errors` destination is not subscribed because its
client wiring was not established by the verified contract. ERROR frames and
session callbacks are the current realtime error path.

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
7. establish realtime connection using the same access JWT
8. enter the fullscreen TUI shell
9. lazily load selected conversation history and subscribe to it
10. send/receive messages through the realtime manager
11. disconnect realtime
12. call server logout
13. clear local session state
14. exit

Exit codes:

- `0` — successful execution
- `1` — authentication/server/runtime failure
- `2` — usage/validation failure

## Testing approach

API tests use the `HttpTransport` seam and a fake transport rather than requiring a running server. Conversation API tests verify exact paths/query parameters, JSON parsing including nullable participant usernames and `LocalDateTime`, malformed responses, transport failures, and 400/401/403/404 mappings. Session tests cover authentication state, token replacement, and authenticated user-id handling. Model tests cover server-order preservation, authoritative message merge/deduplication, and the distinction between server high-water marks and locally loaded sequence. TUI state/controller/renderer/app tests cover keyboard interaction, focus, timestamps, send-state transitions, background repaint, and lifecycle behavior.

Realtime tests cover the transport seam, URL/destination/payload behavior, subscription replacement/deduplication, request-ID generation, authoritative message merge, bounded reconnect/resubscription, history catch-up, notices, and disconnect behavior without requiring a live socket.

A live server smoke test should use disposable data and a real terminal. Phase 5 was manually and automatically verified with two clients: both authenticated, subscribed to the same conversation, exchanged messages in both directions, observed server timestamps and authoritative persistence, and exited through realtime disconnect followed by HTTP logout/session revocation. Temporary fixtures were removed afterward.

The current Phase 5 baseline has **131 automated tests passing**.

## Dependency policy

Keep the client dependency footprint small. Current runtime dependencies are picocli, Jackson (including `jackson-datatype-jsr310` for server `LocalDateTime`), Lanterna, Spring WebSocket, Spring Messaging, and the Tomcat WebSocket implementation used by the standard WebSocket client. JUnit is test-only.

Spring WebSocket/Messaging are used only as client-side protocol libraries. Do not introduce Spring Boot, an embedded server, a broker, ORM/persistence, or another framework to solve a client concern without a concrete requirement.

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

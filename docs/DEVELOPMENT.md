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

Run the CLI:

```bash
./gradlew run --args="--server http://localhost:8080 --username alice"
```

Show CLI help:

```bash
./gradlew run --args="--help"
```

Create the install distribution:

```bash
./gradlew installDist
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

Phase 3 uses Lanterna `3.1.5` for the fullscreen shell. The shell is intentionally separated from transport code:

- `TuiApp` owns terminal lifecycle and render/input loop.
- `TuiController` owns keyboard-to-state transitions.
- `TuiRenderer` owns terminal presentation.
- `TuiLauncher` is the bootstrap seam.
- `PreviewInbox` supplies temporary in-memory display data only.

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

The Phase 3 shell does not call conversation, messaging, realtime, friend,
or search APIs. Preview data must not be treated as server state.

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
5. establish in-memory authenticated session
6. enter the fullscreen TUI shell
7. exit the TUI
8. call server logout
9. clear local session state
10. exit

Exit codes:

- `0` — successful execution
- `1` — authentication/server/runtime failure
- `2` — usage/validation failure

## Testing approach

API tests use the `HttpTransport` seam and a fake transport rather than requiring a running server. Session tests cover authentication state and token replacement/session behavior. TUI state/controller tests exercise keyboard bindings, and renderer tests use a virtual terminal where practical.

A live server smoke test can be used for end-to-end authentication and TUI lifecycle verification. Use disposable test data and never commit credentials or tokens.

## Dependency policy

Keep the client dependency footprint small. Current primary runtime dependencies are picocli, Jackson, and Lanterna. JUnit is test-only. Do not introduce a server framework or persistence technology to solve a client concern without a concrete requirement.

WebSocket/STOMP dependencies belong to a future implementation phase and should be introduced only when realtime capability is actually implemented.

## Implementation workflow

For each phase:

1. establish scope
2. verify relevant Samvaad Server contracts
3. implement the client behavior
4. run tests/build
5. inspect the actual implementation
6. reconcile documentation
7. update the root `README.md` as part of the same documentation gate
8. commit and push
9. verify the repository state

The root README is part of the primary project documentation, not a separate afterthought.

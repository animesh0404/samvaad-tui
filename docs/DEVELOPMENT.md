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

## CLI behavior

Usage:

```text
samvaad-tui [--server URL] [--username USERNAME]
```

If server URL or username is absent, the client prompts for it. The password is collected securely through the console when available; the non-console fallback warns that input may be visible.

Current Phase 2 flow:

1. resolve server URL
2. resolve username
3. collect password
4. call login
5. establish in-memory authenticated session
6. print a sanitized summary
7. call server logout
8. clear local session state
9. exit

Exit codes:

- `0` — successful execution
- `1` — authentication/server/runtime failure
- `2` — usage/validation failure

## Testing approach

API tests use the `HttpTransport` seam and a fake transport rather than requiring a running server. Session tests cover authentication state and token replacement/session behavior.

A live server smoke test can be used for end-to-end authentication verification. Use disposable test data and never commit credentials or tokens.

## Dependency policy

Keep the client dependency footprint small. Current primary dependencies are picocli, Jackson, and JUnit. Do not introduce a server framework or persistence technology to solve a client concern without a concrete requirement.

Lanterna and WebSocket/STOMP dependencies belong to future implementation phases and should be introduced only when those capabilities are actually implemented.

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

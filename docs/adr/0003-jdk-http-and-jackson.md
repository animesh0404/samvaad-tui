# ADR 0003: JDK HTTP Client and Jackson

- Status: Accepted
- Date: 2026-09-14

## Context

Phase 2 requires HTTP communication with the existing Samvaad Server and JSON serialization/deserialization. The TUI does not need a server-side application framework.

## Decision

Use `java.net.http.HttpClient` for HTTP transport and Jackson for JSON serialization/deserialization.

Expose a small `HttpTransport` abstraction with `JdkHttpTransport` as the current implementation. API clients depend on that boundary rather than constructing HTTP mechanics throughout application code.

## Consequences

### Positive

- no HTTP framework required
- small dependency footprint
- explicit transport boundary
- straightforward API testing with a fake transport

### Negative

- conveniences from larger HTTP frameworks must be handled explicitly
- advanced future HTTP requirements may require additional infrastructure

## Scope

This decision applies to the HTTP API layer. It does not prescribe the future realtime implementation.

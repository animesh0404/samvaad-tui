# ADR 0001: Thin Client and Server Authority

- Status: Accepted
- Date: 2026-09-14

## Context

Samvaad TUI is a client of an existing Samvaad Server. The server already owns authentication, authorization, persistence, business rules, domain state, sequencing, idempotency, and other authoritative application behavior.

Duplicating those responsibilities in the TUI would create competing sources of truth and client/server drift.

## Decision

The TUI remains a thin client. The Samvaad Server is authoritative for server-side application state and business behavior.

The TUI owns terminal interaction, client presentation state, session state, and transport adaptation. It consumes verified HTTP and realtime contracts and delegates business decisions to the server.

The TUI must not create a parallel local implementation of server business rules.

## Consequences

### Positive

- one authoritative source of business behavior
- less duplicated logic
- smaller client architecture
- fewer synchronization problems

### Negative

- the TUI depends on server contracts
- client features must be implemented against actual server APIs
- offline business behavior is intentionally limited

## Scope

This decision applies throughout the TUI project. Any future local state must be justified as client/session/presentation state rather than a second authoritative domain store.

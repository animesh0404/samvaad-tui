# ADR 0005: No Local Persistence in V1

- Status: Accepted
- Date: 2026-09-14

## Context

The initial TUI phases require authentication/session state but do not establish a concrete requirement for local application persistence. Adding SQLite or another local datastore prematurely would introduce schema, migration, synchronization, and lifecycle concerns before the client requires them.

## Decision

V1 will not persist authentication tokens, session state, or application/domain data locally unless a concrete requirement is established.

Authentication/session state remains in memory. The Samvaad Server remains the authoritative persistent store.

SQLite is therefore not part of the current implementation.

## Consequences

### Positive

- simpler architecture
- no local database lifecycle
- no token-at-rest storage
- no client/server synchronization layer
- less code and maintenance

### Negative

- client state is lost when the process exits
- future offline or caching requirements may require a new persistence design

## Reconsideration criteria

Persistence may be reconsidered for a concrete requirement such as offline operation, explicit local caching, durable client preferences, or another defined client-side persistence need. Such a change should be introduced deliberately rather than preemptively.

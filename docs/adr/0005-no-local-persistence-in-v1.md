# ADR 0005: No Local Persistence in V1

- Status: Accepted, amended (see Amendment below; original decision preserved)
- Date: 2026-09-14
- Amended: 2026-09-27

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

## Amendment: mandatory E2EE cryptographic persistence exception

E2EE establishes the concrete client-side persistence requirement this ADR
anticipated. A local E2EE device cannot exist without durable custody of its
identity private material, signed-prekey/OTPK/Kyber private material,
Signal sessions, trust verdicts, and outbound/replay state: losing them
across restarts would silently replace the device identity and make existing
encrypted sessions unrecoverable.

The original decision above is otherwise unchanged. In particular:

- Authentication tokens, session state, conversations, messages, friend
  state, and all UI state remain memory-only. The server remains the
  authoritative store for everything except client-owned crypto state.
- This exception does NOT imply SQLite, general application persistence,
  offline operation, or local caching. No such architecture is introduced.
- E2EE state is client-owned and lives only under the platform
  application-data directory (`<base>/samvaad/e2ee`, resolved by
  `E2eePaths`; `%APPDATA%` on Windows, `$XDG_DATA_HOME` or
  `~/.local/share` elsewhere). Never in the repository, build directory,
  or `/tmp`.
- Private key material is encrypted at rest in `FilePrivateKeyVault`,
  unlocked only by an interactively entered vault password. The password is
  never printed, never stored, has no environment fallback, and its array
  is zeroed after use.
- Crypto-store state persists via `FileBackedClientCryptoStore`. Snapshot
  and vault formats/versioning remain owned by the extracted `e2ee-client`
  library, never by the TUI.
- The existing identity is never silently replaced: unknown, corrupt, or
  mismatched state fails closed.

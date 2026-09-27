# ADR 0012: E2EE Device Enrollment and Server Identity

- Status: Accepted
- Date: 2026-09-27

## Context

Slice 1 gave the TUI a persistent local E2EE device (local device id,
registration id, identity/signed/Kyber material in the encrypted vault and
file-backed store). That device is meaningless to the server until it is
enrolled through the verified server device API. The server contract
distinguishes several identifiers and states the TUI must not conflate.

## Decision

### Four identifiers, four owners

- Local device id (UUID): TUI-generated, owns the vault and the local
  store (`device.properties`). Never sent to the server, never replaced.
- libsignal registration id (int): TUI-generated, uploaded once at
  enrollment, echoed by the server. Local copy is authoritative for crypto;
  a server row with the same identity but a different registration id is a
  fail-closed inconsistency.
- Server device id (UUID): assigned by `POST /api/e2ee/devices`, persisted
  locally after enrollment, used in all device-scoped API paths.
- Signal device id (int): server-assigned monotonic integer per account,
  persisted locally, used only inside future Signal addresses.

Enrollment never regenerates identity keys. The local identity predates
enrollment and survives it byte-identically.

### Flow (server contract is authoritative)

1. `GET /api/e2ee/devices` first; a row whose identity public key matches
   the local identity is adopted (status, server ids, prekey count) — no
   second enrollment is ever created for one local identity.
2. Otherwise `POST /api/e2ee/devices` with public material only (identity,
   registration, signed triple, Kyber triple, `TUI`/`samvaad-tui`/`0.1.0`).
   The Kyber triple is provisioned atomically inside enrollment; no
   separate Kyber call exists in this slice.
3. When the server reports the device `ACTIVE`, generate exactly
   `PrekeyManager.BATCH_SIZE` (100) one-time prekeys locally (privates
   sealed in the vault), upload the public halves, persist the high-water
   mark (starting at 1). Uploads are skipped while the server reports a
   non-zero pool.
4. `409` on enrollment reconciles via `GET` instead of regenerating;
   ambiguous transport failures propagate and the next attempt reconciles.
   PENDING stays pending (approval is a server-side act by another active
   device; this client never self-approves).

### Local enrollment states

`LOCAL_ONLY`, `PENDING_APPROVAL`, `ACTIVE`, `REVOKED` — the last-known
server status cached in `device.properties` and refreshed on every
attempt. REVOKED is terminal and never misrepresented as active.

### Recovery codes

First-device bootstrap returns 25 plaintext codes exactly once. They are
returned to the caller for one-time user display and are never persisted
anywhere (not in the vault, not in metadata, not in logs).

## Consequences

- No messaging changes: plaintext STOMP flows are untouched; no
  ciphertext is produced or consumed in this slice.
- No JWT persistence: the existing access token is borrowed per call.
- Replenishment below the policy threshold, Kyber rotation, approval UI,
  and recovery-code flows belong to later slices.

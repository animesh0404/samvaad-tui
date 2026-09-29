# ADR 0013: Encrypted Direct-Message Sending

- Status: Accepted
- Date: 2026-09-27

## Context

Slices 0–2 provide the shared crypto library, the persistent local
device, and server enrollment. This slice adds the first real
ciphertext path: `TUI → SamvaadCryptoService → LibSignalAdapter →
POST /api/e2ee/messages`. Inbound decryption/mailbox remains a later
slice.

## Decision

### Send flow (server contract authoritative)

1. Recipient directory: `GET /api/e2ee/users/{username}/devices`. Every
   returned active device is fanned out to; none is silently skipped.
   The recipient user id comes from the conversation/friend record (the
   directory never carries it) for `CryptoTypes.RecipientBundle`
   construction.
2. Claims: `POST /api/e2ee/devices/{id}/one-time-prekeys/claim` with the
   service-owned deterministic `claimRequestId` forwarded verbatim
   (replays return the same prekey; never mint new ids). Signed-prekey
   fallback when the claim carries no OTPK.
3. `SamvaadCryptoService.sendToDevices` owns establishment, encryption,
   envelope-type selection (`PREKEY_INIT`/`RATCHET` used verbatim), and
   the crash-safe outbound machine (`commitOutboundCiphertext`,
   byte-identical replay). The TUI only provides the `ClaimClient` /
   `SubmitClient` seams and maps library envelopes to the submit DTO.
4. Submit: `POST /api/e2ee/messages` with `{messageRequestId, envelopes}`
   (sender/recipient device ids, envelope type, Base64 ciphertext). Both
   201 and 200 (idempotent replay) are success. The server finds or
   creates the direct conversation, so no plaintext first-message call
   is involved. Transport/5xx failures map to the library's transient
   path (slots stay COMMITTED; retry with the same request id replays
   identical bytes); 4xx surfaces terminally without rollback games.

### Application payload (new, minimal, versioned)

`[version=1][type=1 direct text][UTF-8 content]`, framed by `E2eePayload`
before encryption and independent of Signal so future Web clients can
decode it. Strict on decode. Content parity mirrors the 200-char
composer; no separate cap is invented.

### Boundaries

- UI operates on conversation/message text only (`Ctrl+E` in the
  composer, `Encrypting...`/`Sent (encrypted, seq N).` statuses). The
  plaintext `Enter` path is untouched; the two paths never mix and E2EE
  never falls back to plaintext.
- The TUI never calls `LibSignalAdapter` from messaging/UI layers; no
  private bytes/handles cross HTTP; no JWT is persisted.

### Known lifecycle limitation (server model, not a bug)

Ciphertext submit requires the calling session to be bound to the
sender device, and binding happens only at enrollment — there is
intentionally no bind-existing-session endpoint. A TUI launch therefore
sends only when it freshly enrolled this session; adopted devices
report precisely why they cannot submit. Returning-session sending
needs a future server-supported binding/recovery flow, not client
workarounds. First contact still uses the plaintext first-message
endpoint (the E2EE action targets existing conversations); making
first contact encrypted is future work.

## Consequences

- The server receives ciphertext only on this path.
- `E2eeMessageSender.disabled(reason)` keeps unreadiness explicit.
- Replenishment, rotation, approval UI, recovery flows, and inbound
  processing belong to later slices.


## Amendment (2026-09-29): inbound transport and recovery rebind

The previously deferred inbound E2EE transport is now implemented in the TUI: mailbox fetch, acknowledgement, encrypted history reads, synchronization-cursor operations, local decryption/merge, and guarded background mailbox refresh are all part of the current runtime.

The server recovery-rebind endpoint resolves the earlier adopted-device/session-binding limitation described above.

The current UI still exposes the legacy plaintext send path separately. This is current implementation state, not the final E2EE-only product contract.

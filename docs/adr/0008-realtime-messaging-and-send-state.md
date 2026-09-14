# ADR 0008: Realtime Messaging and Authoritative Send State

- Status: Accepted
- Date: 2026-09-14

## Context

Phase 5 adds message sending and realtime delivery to the TUI using the
existing Samvaad Server WebSocket/STOMP contract. The server is authoritative
for message identity, sequencing, timestamps, sender identity, persistence,
and idempotency.

The verified realtime contract is:

- WebSocket endpoint: `/ws`
- STOMP CONNECT authorization: `Authorization: Bearer <access-token>`
- send destination: `/app/chat.send`
- conversation subscription: `/topic/conversations/{conversationId}`

The server broadcast contains the authoritative persisted message. The TUI
also needs to remain responsive while realtime callbacks and HTTP catch-up
arrive asynchronously.

## Decision

### Transport boundary

Keep WebSocket/STOMP protocol mechanics behind the `realtime` package:

```text
TuiApp / TuiController
        |
        v
 RealtimeManager
        |
        v
 RealtimeClient
        |
        v
 SpringRealtimeClient
        |
        v
 WebSocket + STOMP
```

`SpringRealtimeClient` uses Spring WebSocket/Messaging and the standard
WebSocket client implementation as client-side libraries only. The TUI does
not become a Spring Boot application and does not host an embedded server.

### Authentication

Use the same server-issued access JWT for authenticated HTTP and STOMP
CONNECT. The realtime manager retains the token internally; UI presentation
classes do not receive raw authentication credentials.

### Sending

A send supplies only the server-contract fields: conversation ID, content,
and a fresh UUID `requestId`. The client does not generate message IDs,
sequence numbers, timestamps, or sender identity.

Sending is non-optimistic. The UI may show `Sending...`, but it transitions to
`Sent` only when the authoritative realtime message carrying the matching
request ID has been merged into the store. This prevents a local transport
handoff from being treated as proof of server persistence.

The client does not retry sends because no verified server retry/idempotency
protocol beyond the request ID contract was established for the client.

### Message merge and ordering

`ConversationStore` merges incoming realtime/history messages by server-owned
message ID and preserves ascending server sequence order. Duplicate delivery
of the same message therefore does not create duplicate UI entries.

The store retains two sequence concepts:

- `lastSequenceNumber` — server-provided conversation high-water mark;
- `highestLoadedSequence` — highest sequence actually present in local state.

The client never derives or increments server sequence numbers itself.

### Subscription lifecycle

The realtime manager maintains the subscription for the currently selected
conversation. Switching conversations replaces the prior subscription and
repeated subscription of the same conversation is a no-op.

On unexpected socket loss, the manager performs bounded reconnect using the
same access token, restores the selected conversation subscription, and uses
the existing HTTP history seam with `afterSequence=highestLoadedSequence` for
catch-up. The transport itself does not implement retry policy.

### UI threading

Realtime callbacks run outside the terminal UI thread and update synchronized
client state. The TUI uses a short polling render loop so background history
loads and realtime messages repaint even while the user is idle.

### Error handling

Realtime errors are surfaced as concise, token-free user notices. The TUI
does not subscribe to `/user/queue/errors` because that client wiring was not
established by the verified server contract. STOMP ERROR frames and session
callbacks are the supported error path.

If a send is silently dropped and no authoritative broadcast arrives, the TUI
leaves the send pending rather than fabricating success.

## Consequences

### Positive

- realtime protocol code is isolated and unit-testable;
- the same authenticated session is used consistently for HTTP and realtime;
- server identity, timestamps, sequencing, and persistence remain authoritative;
- duplicate broadcasts are safe at the UI state boundary;
- reconnect can recover missed messages through the existing history API;
- the terminal remains responsive during asynchronous work;
- send status reflects authoritative server observation rather than optimistic assumptions.

### Negative

- Spring WebSocket/Messaging and a WebSocket implementation add runtime dependencies;
- in-session socket-drop recovery is more complex than a one-shot connection;
- a silently dropped send can remain `Sending...` because there is no verified client-visible error/ack path for that case;
- realtime reconnect is bounded rather than guaranteed indefinitely;
- the current implementation has not live-tested an isolated mid-session socket drop.

## Scope

This decision applies to Phase 5 realtime messaging and sending. It does not
define friend/search/request workflows, local persistence, offline sync, or new
server endpoints. Any future expansion of the realtime protocol must first be
grounded in verified Samvaad Server contracts.

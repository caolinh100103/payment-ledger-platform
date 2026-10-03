# ADR 0005: Idempotency keys stored in the same transaction as the operation

**Status:** Accepted (Phase 2)

## Context

Networks fail after the server has committed but before the client sees the response. Mobile apps retry,
users double-tap "Pay", and bank webhooks are delivered at least once. Without protection, every retry of a
transfer or deposit moves money again.

## Decision

Every money-moving `POST` (`/deposits`, `/transfers`, `/transfers/{id}/reversals`) requires an
`Idempotency-Key` header. The semantics follow the IETF draft
*The Idempotency-Key HTTP Header Field* and Stripe's API:

| Situation | Response |
|---|---|
| Missing or malformed key (1–255 visible ASCII characters) | `400 IDEMPOTENCY_KEY_MISSING` / `IDEMPOTENCY_KEY_INVALID` |
| New key | Execute, store the response |
| Same key, same request, completed | Stored status, `Location` and body, byte for byte, plus `Idempotent-Replayed: true` |
| Same key, different body, path or method | `422 IDEMPOTENCY_KEY_REUSED` |
| Same key while the first request is still running | `409 IDEMPOTENCY_KEY_IN_PROGRESS` + `Retry-After` |

"Same request" means the same SHA-256 fingerprint of method, path and the validated JSON body.

### How it works

1. **Claim** the key in its own short transaction: `INSERT ... ON CONFLICT DO NOTHING` with status
   `PROCESSING`, a random `lock_token` and a lease (`locked_until`, 30 s).
2. **Execute** the operation and **store the response in the same transaction**, with
   `UPDATE ... WHERE lock_token = :mine`. Either the transfer and its stored response both commit, or
   neither does. There is no window in which a transfer exists but a retry would not find its response.
3. If the operation throws (validation, not found, lock timeout), nothing was committed, so the key is
   **released** and the client may retry with the same key.

### Crash recovery

If a process dies while holding a key, the key stays `PROCESSING` until its lease expires. After that, a
retry with the same request may take it over with a new `lock_token`. If the original request was in fact
still alive, its final `UPDATE ... WHERE lock_token = :mine` matches no row, so it rolls back. Two
executions can therefore never both commit.

### What is stored

Only outcomes that left a trace: a `COMPLETED` transfer (201) or a `FAILED` one (422 with the failure
code). A `FAILED` outcome is replayed too, even if the situation has changed since: the client asked
"do this transfer" once and got a final answer. A new attempt needs a new key.

### Retention

Keys are kept for 24 hours, the same as Stripe. After that they are ignored when claiming and deleted by a
scheduled job in batches of 1,000.

## Alternatives considered

- **Unique constraint on the business table only** (e.g. a `client_reference` column on `transfers`).
  Stops duplicates but cannot replay the original response, and does not cover requests that fail.
- **Redis** (`SET NX` + TTL). Fast, but it is a second store: Redis and PostgreSQL cannot commit
  atomically, so a crash between them reopens the duplicate window that this design closes. Redis remains
  an option later as a cache in front of the table, never as the source of truth.
- **Holding the key with `INSERT` inside the business transaction** and letting a concurrent duplicate
  block on the unique index. Simpler, but the duplicate waits instead of getting a fast `409`, and a slow
  first request ties up a connection for each retry.

## Consequences

- Clients must generate a key per logical operation (a UUID v4) and reuse it on every retry of that
  operation. A deposit integration uses the bank's transaction reference.
- Until authentication arrives in Phase 4, all keys share one scope (`anonymous`). Then the scope becomes
  the authenticated client, so two clients cannot collide on the same key.
- One extra short transaction per request for the claim.

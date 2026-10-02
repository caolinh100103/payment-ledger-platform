# ADR 0004: Pessimistic row locking in id order for balance changes

**Status:** Accepted (Phase 2)

## Context

A transfer reads two balances, checks the rules (status, currency, funds), then writes both balances and
two ledger entries. Concurrent transfers touching the same account must not overspend it or lose an
update, and two transfers in opposite directions (A→B and B→A) must not deadlock.

The options considered:

1. **`SERIALIZABLE` isolation.** Correct, but PostgreSQL aborts conflicting transactions with
   serialization failures that the application must retry. Under contention that is the same retry storm
   as optimistic locking, and the failures also hit unrelated read queries in the same transaction.
2. **Optimistic locking** (`@Version` check at commit, retry on conflict).
3. **Pessimistic row locks** under the default `READ COMMITTED`.
4. **A single atomic statement** such as `UPDATE accounts SET balance = balance - :x WHERE id = :id AND
   balance >= :x`. Fast, but the business rules (status, currency, account type) and the ledger entry with
   the resulting balance then have to be squeezed into SQL, and recording a FAILED attempt with its reason
   gets awkward.

## Decision

- Default `READ COMMITTED` isolation, and lock both account rows with
  `SELECT ... FOR NO KEY UPDATE` **before** reading them. In `READ COMMITTED`, PostgreSQL returns the latest
  committed version of a row once its lock is granted, so the rules always see the current balance.
- `FOR NO KEY UPDATE` rather than `FOR UPDATE`: it is the lock a plain `UPDATE` of a non-key column takes
  anyway. It serialises balance changes but does not block other transactions that insert rows referencing
  the account by foreign key (`transfers`, `ledger_entries`); those only need `FOR KEY SHARE`.
- **Lock in ascending account id order**, whatever the direction of the transfer. Every transaction
  acquires locks in the same global order, so no wait cycle, and no deadlock, can form.
- **Bound the wait** with `SET LOCAL lock_timeout = '3s'` (configurable). A transaction that cannot get a
  lock fails fast with `503 LOCK_TIMEOUT` and `Retry-After`. Nothing was committed, so the client retries
  with the same idempotency key. This prevents a stuck transaction from exhausting the connection pool.
- **Reversals** lock the original transfer row first, then the accounts. Regular transfers never lock
  transfer rows, so this does not introduce a cycle either.
- **`@Version` stays on `accounts`** as a second line of defence: if a future code path forgets the row
  lock, a lost update becomes a `409` instead of silently wrong money. Removing the lock in a mutation test
  produced exactly those 409s.
- **The database is the last line of defence:** `CHECK (type = 'SYSTEM' OR balance >= 0)` and a deferred
  constraint trigger that rejects any transfer whose entries do not balance.

## Evidence

- [Benchmark](../benchmarks/locking.md): pessimistic locking gives 37% more throughput at medium
  contention and 54% more on a hot account, where optimistic locking fails about 10% of payments even
  after 20 retries. Optimistic locking only wins (about 14%) when conflicts are rare.
- `ConcurrentTransferIntegrationTest` runs 100 HTTP clients against the real server, including an A→B /
  B→A storm. It checks the ledger invariants and PostgreSQL's deadlock counter afterwards. Switching the
  locker to request order makes PostgreSQL report `deadlock detected` and the test fail.

## Consequences

- Throughput on a single hot account is bounded by one row lock: every transfer touching it queues. In the
  benchmark that is about 280 transfers/s on one laptop.
- Known ways to relieve hot accounts, for when it matters:
  - **Shard the hot account** into N sub-accounts, pick one at random per posting, and sum them for the
    balance.
  - **Do not lock the SYSTEM side:** SYSTEM accounts may go negative, so no rule reads their balance.
    Their balance can be derived from the ledger, or updated asynchronously in batches.
  - **Batch postings** for very high volumes, the way card networks settle in batches. TigerBeetle-style
    ledgers take this furthest.
- Locks are held for the whole transaction, so the transaction must stay short: no remote calls inside it.
  Event publication goes through the outbox ([ADR 0003](0003-synchronous-ledger-with-outbox.md)) for this
  reason.

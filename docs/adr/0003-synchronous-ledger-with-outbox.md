# ADR 0003: Write the ledger synchronously; publish events via a transactional outbox

**Status:** Accepted (implemented in Phases 2–3)

## Context

Two failure modes threaten correctness:

1. **Asynchronous ledger.** If the ledger only updates balances after consuming a Kafka event, the
   transfer step cannot know the true balance, so concurrent transfers can overspend.
2. **Dual write.** Committing to PostgreSQL and then publishing to Kafka are two separate operations.
   A crash between them either loses the event or publishes an event for a rolled-back transfer.

## Decision

- The transfer, its debit/credit ledger entries and the balance updates are written in **one database
  transaction**, with the involved account rows locked (`SELECT ... FOR UPDATE`, in ascending id order
  to avoid deadlocks).
- Domain events are inserted into an `outbox` table **in that same transaction**. A relay publishes
  outbox rows to Kafka and marks them as sent ([ADR 0007](0007-polling-outbox-relay.md)).

## Consequences

- Balances are always consistent with the ledger; overdraft is impossible even under concurrency.
- Delivery to Kafka is at-least-once, so every consumer must be idempotent.
- Event publication has a small delay (relay polling interval).

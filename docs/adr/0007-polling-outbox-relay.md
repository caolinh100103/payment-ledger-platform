# ADR 0007: Polling outbox relay with `SKIP LOCKED`

**Status:** Accepted (Phase 3)

## Context

[ADR 0003](0003-synchronous-ledger-with-outbox.md) decided that events are inserted into an `outbox` table in the
same transaction as the change they describe. Something must then move them to Kafka. That component must:

- never lose an event, including while Kafka is down for minutes;
- keep the events of one transfer in order (`created` before `completed` before `reversed`);
- run on every instance of the core service, so there is no single relay process to fail;
- add as little infrastructure as possible.

## Decision

A **polling relay** runs inside the core service ([`OutboxRelay`](../../backend/src/main/java/com/payledger/outbox/OutboxRelay.java)).
Every instance polls every 200 ms and drains while there is work:

```sql
SELECT id, aggregate_id, topic, payload
FROM outbox o
WHERE published_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM outbox older            -- only the oldest pending event
                  WHERE older.aggregate_id = o.aggregate_id  -- of each aggregate
                    AND older.published_at IS NULL
                    AND older.id < o.id)
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED
```

In the same transaction it sends the batch to Kafka, waits for the acknowledgements and sets `published_at`
on the rows Kafka accepted.

### Why the order holds

A transfer's events reach consumers in order because three links each keep it:

1. **Writing.** Every event of a transfer is written by a transaction that holds that transfer's row lock
   (creating it, or `FOR NO KEY UPDATE` in a reversal). For one aggregate, `id` order is therefore commit
   order. Ids of *different* aggregates can commit out of order, but that does not matter.
2. **Relaying.** `SKIP LOCKED` lets instances take disjoint batches without waiting for each other. On its own
   it would break ordering: instance A locks event 1 of a transfer, instance B skips it and publishes
   event 2 first. The `NOT EXISTS` clause prevents that, because an event is only eligible once every older
   event of its aggregate is published. `OutboxRelayIntegrationTest` runs 4 relays against 40 × 5 interleaved
   events. With the clause removed, the test fails on every run.
3. **Kafka.** The message key is the aggregate id, so all its events go to one partition. The producer runs
   with `acks=all` and `enable.idempotence=true`, so its internal retries cannot reorder or duplicate
   messages within a partition.

### Failure handling

| Situation | Behaviour |
|---|---|
| Kafka down or unreachable | Sends time out (`max.block.ms` 5 s, `delivery.timeout.ms` 10 s). Rows stay pending with `attempts` + `last_error`. The API keeps accepting transfers. When Kafka is back, everything is published. |
| One event can never be published (e.g. invalid topic) | Only that aggregate is held back. Other transfers keep flowing. `attempts` grows, which is the alert signal (Phase 5). |
| Relay crashes after Kafka acknowledged but before the commit | The rows are still pending and get published again. **Delivery is at-least-once**, so consumers deduplicate on the CloudEvents `id`. |
| A send times out, but the broker did receive it (e.g. a frozen broker resumes) | The relay counts it as failed and sends it again, so Kafka holds the event twice. The idempotent producer cannot prevent this, because it gives up on a batch once `delivery.timeout.ms` expires. Order is unaffected: the copy still comes before the aggregate's next event. `KafkaOutageIntegrationTest` hits this case regularly. |
| Several instances | They share the work through `SKIP LOCKED`. There is no leader election and no failover delay. |

Published rows are kept for 7 days, for debugging and manual replay, and then deleted in batches. Pending
rows are never deleted.

## Alternatives considered

- **Log-based CDC with Debezium** (outbox event router). Debezium reads the PostgreSQL WAL, so there is no
  polling load, latency is lower, and order follows commit order. This is the most common production choice
  at scale. It costs a Kafka Connect cluster and a logical replication slot. A stalled slot makes PostgreSQL
  keep WAL until the disk fills, so the slot needs monitoring. For one service at this volume, that
  operational weight is not justified. The table layout (`aggregate_id`, `event_type`, `payload`) matches
  Debezium's outbox router, so switching later only replaces the relay.
- **Kafka transactions.** These make *Kafka* writes and consumer offsets atomic with each other. They cannot
  include a PostgreSQL commit (Kafka has no XA support), so "commit to the DB, then commit to Kafka" is still
  a dual write. They solve exactly-once processing between Kafka topics, which is a different problem.
- **Publish after commit** (`@TransactionalEventListener(AFTER_COMMIT)`). A crash between the commit and the
  send loses the event. Spring Modulith's event publication registry fixes that by persisting the event
  first, which is an outbox again.
- **One relay with leader election** (e.g. `pg_try_advisory_lock`). Ordering is trivial with one publisher,
  but a single instance does all the work, and a failover waits for the old leader's session to die.
- **`LISTEN/NOTIFY`** to wake the relay instead of polling. This is a possible latency optimisation later.
  Polling stays as the safety net, because notifications are lost while no listener is connected.

## Consequences

- Consumers must be idempotent (ADR 0008).
- Event latency is about one poll interval (≤ 200 ms). A transfer's `completed` event goes out one relay
  round after its `created` event, because only one event per aggregate is eligible per round. The relay
  drains without sleeping, so that round is milliseconds.
- The relay holds a database connection and row locks on outbox rows while it waits for Kafka, at most the
  15 s send timeout. Business transactions only `INSERT` into the outbox, so they never wait for it.
- Idle cost: one partial-index scan per instance every 200 ms.

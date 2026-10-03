# ADR 0008: Idempotent consumers, non-blocking retry topics and dead-letter topics

**Status:** Accepted (Phase 3)

## Context

The outbox relay delivers each event **at least once** ([ADR 0007](0007-polling-outbox-relay.md)). Consumers see
the same event again when:

- the relay crashes after Kafka acknowledged a batch but before it marked the rows published;
- a consumer crashes after processing a record but before committing its offset;
- a consumer group rebalances while records are in flight.

Processing can also fail, in two very different ways:

| Kind | Examples | Will a retry help? |
|---|---|---|
| Transient | Database failover, SMS gateway timeout, network blip | Yes, after a pause |
| Permanent | Not JSON, missing CloudEvents attributes, a `schemaversion` this consumer cannot read | Never |

## Decision

### 1. Idempotent consumers: deduplicate on the event id, in the same transaction as the effect

| Consumer | "Already processed" record | Written with |
|---|---|---|
| notification-service | `processed_events (consumer, event_id)` primary key | `INSERT … ON CONFLICT DO NOTHING`, in the transaction that stores the notifications |
| audit-service | `audit_events.event_id` unique | The audit row itself, under the chain lock ([ADR 0009](0009-tamper-evident-audit-log.md)) |

The marker and the effect commit together. If the effect fails, the marker rolls back and the retry runs again.
If both committed, every later copy is skipped. Two copies processed **concurrently** cannot both act: the
second `INSERT` waits on the first transaction's key, then sees the conflict. `IdempotentConsumerIntegrationTest`
sends 10 concurrent copies and checks that exactly one acts. With the check removed, all four idempotency
tests fail.

Offsets are committed per record, after the database commit (`ack-mode: record`).

`processed_events` rows are kept for **14 days**, longer than the topic's 7-day retention. Forgetting an event
that Kafka can still redeliver would let it act twice.

**What stays at-least-once:** the external send. If the process dies after the SMS gateway accepted a message
but before the commit, the retry sends it again. A gateway that accepts a client reference (the notification id)
can drop the repeat. Otherwise a rare duplicate SMS is the accepted cost. Financial effects never live in
consumers: money moves only in the core, synchronously ([ADR 0003](0003-synchronous-ledger-with-outbox.md)).

### 2. Non-blocking retries on retry topics, then a dead-letter topic

This is the pattern Uber described in *Building Reliable Reprocessing and Dead Letter Queues with Apache Kafka*
(2018). Spring Kafka implements it as `@RetryableTopic`. A failed record is republished to a retry topic and
consumed again after its back-off, while the main topic keeps flowing:

| Service | Attempts | Back-off | Topics |
|---|---|---|---|
| notification | 4 | 1 s, 2 s, 4 s | `payledger.transfers-notification-retry-0..2`, `…-notification-dlt` |
| audit | 5 | 2 s, 6 s, 18 s, 54 s (rides out a DB failover) | `payledger.transfers-audit-retry-0..3`, `…-audit-dlt` |

- **Permanent failures skip the retries.** `MalformedEventException` and `UnsupportedEventException` go
  straight to the dead-letter topic: retrying a message that cannot be parsed only delays the alert.
- **Retry topics are named per consumer.** Both services consume `payledger.transfers`. With Spring's default
  suffixes they would share `payledger.transfers-retry-*`, and each service would consume the other's retries.
- **The dead-letter topic is not a bin.** Each record keeps its original key and value, plus headers with the
  original topic, partition and offset, the exception class, message and stack trace, and the number of attempts.
  The `@DltHandler` logs it as an error, which is what an alert fires on (Phase 5). After a fix, the records are
  replayed onto the main topic. Replay is safe because consumers are idempotent.

`RetryAndDeadLetterIntegrationTest` and `AuditRetryIntegrationTest` cover four cases against a real broker: a
transient failure that succeeds on a retry, one that ends in the dead-letter topic after every attempt, and a
malformed message and a schema-v2 event that are parked after a single attempt.

## Alternatives considered

- **Blocking retries** (`DefaultErrorHandler` with back-off on the main topic). These keep strict per-key order,
  but one bad record stops its whole partition for every transfer behind it (head-of-line blocking), and a long
  back-off can exceed `max.poll.interval.ms` and trigger a rebalance. That suits consumers that need strict order
  (e.g. applying balance snapshots). Ours do not.
- **Log and skip.** This loses notifications and, worse, leaves silent gaps in the audit trail.
- **Retry forever.** A poison message would cycle forever and never alert anyone.
- **Kafka exactly-once (transactions).** This only covers Kafka-to-Kafka processing. Our effects go to
  PostgreSQL and to an SMS gateway, outside any Kafka transaction.

## Consequences

- **Order is not guaranteed after a failure.** While one event of a transfer waits on a retry topic, later events
  of the same transfer can be processed. Notifications may then arrive out of order (rare and harmless). The audit
  chain records events in the order they were recorded, and `occurred_at` keeps the real time.
- Each consumer group gets `attempts - 1` retry topics plus one dead-letter topic.
- A Spring Kafka detail found by the tests: `@KafkaListener(id = …)` also sets the **consumer group** to that
  id, which silently overrides `spring.kafka.consumer.group-id`. Both listeners set `idIsGroup = false`.

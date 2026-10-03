# Payment Processing & Ledger Platform

A banking-oriented payment platform: accounts, money transfers with a double-entry ledger,
idempotent APIs, concurrency-safe balance updates, and event-driven downstream processing.

> Status: **Phase 3 – Events** done (transactional outbox, CloudEvents on Kafka, tamper-evident audit
> service, notification service, idempotent consumers, retry topics and dead-letter topics).
> Next: Phase 4 – security.

## Architecture

```
React (TS) ──► API Gateway (JWT, rate limit, API key)
                    │
                    ▼
            Core Service (Spring Boot, modular monolith)
            ├── account    ✅
            ├── transfer   ✅ ─┐
            ├── ledger     ✅ ─┤ one DB transaction
            └── outbox     ✅ ─┘
                    │
              PostgreSQL ◄── Outbox relay ✅ ──► Kafka: payledger.transfers (CloudEvents)
                                                   ├── Notification Service ✅ (own DB)
                                                   └── Audit Service ✅ (own DB, hash chain)
            Redis: rate limiting
            Prometheus + Grafana: metrics
```

Key design decisions are recorded in [docs/adr](docs/adr):

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-modular-monolith-first.md) | Modular monolith first |
| [0002](docs/adr/0002-money-as-minor-units.md) | Money as integer minor units |
| [0003](docs/adr/0003-synchronous-ledger-with-outbox.md) | Synchronous ledger, transactional outbox for events |
| [0004](docs/adr/0004-pessimistic-row-locking-for-transfers.md) | Pessimistic row locks in id order ([benchmark](docs/benchmarks/locking.md)) |
| [0005](docs/adr/0005-idempotency-keys.md) | Idempotency keys stored in the same transaction as the operation |
| [0006](docs/adr/0006-double-entry-ledger-model.md) | Double-entry ledger model, SYSTEM accounts, reversals |
| [0007](docs/adr/0007-polling-outbox-relay.md) | Polling outbox relay with `SKIP LOCKED`, ordered per aggregate |
| [0008](docs/adr/0008-idempotent-consumers-retry-topics-dlq.md) | Idempotent consumers, non-blocking retry topics, dead-letter topics |
| [0009](docs/adr/0009-tamper-evident-audit-log.md) | Tamper-evident audit log in its own service |

The event contract is documented in [docs/events.md](docs/events.md).

## Tech stack

Java 21 · Spring Boot 4.1 · PostgreSQL 17 · Flyway · Kafka 4 (KRaft) · Redis · Testcontainers ·
Prometheus · Grafana · Docker Compose · GitHub Actions

## Getting started

Prerequisites: JDK 21, Docker.

```bash
# 1. Start infrastructure
cd infra
docker compose up -d --wait

# 2. Run the core service
cd ../backend
./mvnw spring-boot:run

# 3. (Optional) Run the consumers, each in its own terminal
cd ../services/audit-service && ./mvnw spring-boot:run
cd ../services/notification-service && ./mvnw spring-boot:run
```

| Service     | URL                                  | Notes                       |
|-------------|--------------------------------------|-----------------------------|
| Core API    | http://localhost:8080/api/v1         |                             |
| Audit API   | http://localhost:8082/api/v1/audit-events |                        |
| Notifications API | http://localhost:8083/api/v1/notifications |                 |
| Metrics     | http://localhost:{8080,8082,8083}/actuator/prometheus |            |
| PostgreSQL (core) | localhost:15432                | payledger / payledger       |
| PostgreSQL (audit) | localhost:15433               | audit_owner (migrations), audit_app (app) |
| PostgreSQL (notification) | localhost:15434        | notification / notification |
| Redis       | localhost:16379                      |                             |
| Kafka       | localhost:29092                      |                             |
| Kafka UI    | http://localhost:8081                |                             |
| Prometheus  | http://localhost:9090                |                             |
| Grafana     | http://localhost:3000                | admin / admin               |

Host ports are shifted from the defaults so they don't clash with locally installed services;
override them with `POSTGRES_PORT`, `REDIS_PORT`, `KAFKA_PORT`, etc.

## API

### Accounts

| Method | Path                                 | Description                          |
|--------|--------------------------------------|--------------------------------------|
| POST   | `/api/v1/accounts`                   | Open a customer account (`ownerId`, `currency`) |
| GET    | `/api/v1/accounts/{id}`              | Get account and balance              |
| GET    | `/api/v1/accounts?ownerId=`          | List an owner's accounts             |
| POST   | `/api/v1/accounts/{id}/freeze`       | ACTIVE → FROZEN                      |
| POST   | `/api/v1/accounts/{id}/unfreeze`     | FROZEN → ACTIVE                      |
| POST   | `/api/v1/accounts/{id}/close`        | → CLOSED (balance must be zero)      |

### Money movement

All three `POST`s require an `Idempotency-Key` header.

| Method | Path                                 | Description                          |
|--------|--------------------------------------|--------------------------------------|
| POST   | `/api/v1/deposits`                   | Credit a customer with money received from outside (bank top-up) |
| POST   | `/api/v1/transfers`                  | Move money between two customer accounts |
| GET    | `/api/v1/transfers/{id}`             | Get a deposit, transfer or reversal, including rejected ones |
| POST   | `/api/v1/transfers/{id}/reversals`   | Reverse a completed deposit or transfer (`reason`) |

```bash
curl -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c2a9e-0d1b-4c55-9b3e-7a2f8e4d1c00' \
  -d '{"sourceAccountId": "…", "destinationAccountId": "…",
       "amount": 250000, "currency": "VND", "description": "Rent October"}'
```

Amounts are integers in the currency's minor unit. `currency` is required so a client cannot move the
wrong value by mistaking the unit.

A transfer rejected by a business rule is **recorded** as `FAILED` and returned as `422` with a stable
`code` and the `transferId`:

```json
{
  "status": 422,
  "title": "Unprocessable Content",
  "detail": "Account 3f2a… has insufficient funds for 250000 VND",
  "code": "INSUFFICIENT_FUNDS",
  "transferId": "9b0e…"
}
```

| Code | Meaning |
|---|---|
| `INSUFFICIENT_FUNDS` | The source account does not hold the amount |
| `SOURCE_ACCOUNT_NOT_ACTIVE` / `DESTINATION_ACCOUNT_NOT_ACTIVE` | An account is frozen or closed |
| `CURRENCY_MISMATCH` | An account is not in the requested currency |
| `ACCOUNT_TYPE_NOT_ALLOWED` | Customer transfers cannot touch SYSTEM accounts |
| `TRANSFER_NOT_REVERSIBLE` | Only COMPLETED, non-reversal transfers can be reversed |
| `LOCK_TIMEOUT` (503) | An account stayed locked too long; retry with the same key |

### Idempotency

Follows the IETF *Idempotency-Key* draft with Stripe-style semantics ([ADR 0005](docs/adr/0005-idempotency-keys.md)):

| Retry with the same key | Response |
|---|---|
| Same request, completed | Original response replayed byte for byte, `Idempotent-Replayed: true` |
| Different request | `422 IDEMPOTENCY_KEY_REUSED` |
| First request still running | `409 IDEMPOTENCY_KEY_IN_PROGRESS` + `Retry-After` |

### Audit and notifications

| Method | Path                                         | Service | Description |
|--------|----------------------------------------------|---------|-------------|
| GET    | `/api/v1/audit-events?resourceId=`           | audit (8082) | The audit trail of a transfer, oldest first |
| GET    | `/api/v1/audit-events/verification`          | audit (8082) | Re-hash the whole chain; reports the first altered or missing record |
| GET    | `/api/v1/notifications?recipientId=`         | notification (8083) | A customer's messages, newest first |

## How the money stays correct

- **Double entry.** Every movement posts one DEBIT and one CREDIT of the same amount. Deposits debit a
  per-currency SYSTEM funding account, so every currency's balances always sum to zero.
- **Database-enforced invariants.** Customer balances have a `CHECK (balance >= 0)`. Ledger entries are
  append-only (triggers block UPDATE/DELETE/TRUNCATE). A deferred constraint trigger rejects any
  transfer whose entries do not balance at commit.
- **Pessimistic locking.** Both accounts are locked `FOR NO KEY UPDATE` in ascending id order under
  `READ COMMITTED`, with a bounded `lock_timeout`. Opposite transfers cannot deadlock.
- **Corrections are new entries.** A reversal posts compensating entries. Original entries never change.

## How the events stay reliable

```
transfer tx ──► outbox row ──► relay (every instance, SKIP LOCKED) ──► Kafka ──► consumer tx
   one commit                  oldest pending event per transfer       key =     processed_events +
                                                                       transfer   side effect, one commit
```

- **No dual write.** Each transfer writes its events (`created`, then `completed` or `failed`, and later
  `reversed`) to an `outbox` table in its own transaction. An event exists if and only if its change committed.
- **Ordered and scalable.** Every instance runs the relay. `FOR UPDATE SKIP LOCKED` splits the work, and only
  the oldest pending event of each transfer is eligible, so a transfer's events always reach Kafka in order.
  Kafka keeps that order because the transfer id is the message key.
- **Kafka can go down.** Payments never wait for the broker. Events wait in the outbox and are published when
  the broker is back (see the demo below).
- **At least once in, at most once out.** Consumers record the event id in the same transaction as their effect,
  so a redelivered event is skipped.
- **Failures don't block the topic.** A failing event moves to a per-service retry topic with back-off (Uber's
  retry-topic pattern via Spring Kafka `@RetryableTopic`). It ends on a dead-letter topic if it never succeeds.
  Malformed events and unknown schema versions go straight to the dead-letter topic.
- **Versioned contract.** Events are [CloudEvents 1.0](https://cloudevents.io) with a `schemaversion`
  extension. Consumers are tolerant readers and park versions they do not know ([docs/events.md](docs/events.md)).

### Audit trail

The audit service keeps its own database and records every event in `audit_events`, protected by three layers:

| Layer | Protects against |
|---|---|
| The app role `audit_app` has only `SELECT, INSERT`. Migrations run as `audit_owner`. | A compromised or buggy application |
| Triggers reject `UPDATE`, `DELETE` and `TRUNCATE` for every role | Mistakes by operators and owners |
| A SHA-256 hash chain over a gapless `seq` | A superuser who disables the triggers: an edit, a re-hash or a deletion is **detected** by `/verification` |

### Notifications

The notification service sends the balance-change SMS that Vietnamese banks send after every movement:

```
PayLedger: TK ...8b10 -250,000 VND luc 15:15 03/10/2026. SD: 750,000 VND. ND: Tien nha thang 10
```

The text has no diacritics, because Vietnamese characters force Unicode SMS (70 characters per segment instead
of 160). Delivery is simulated by logging.

## Testing

```bash
cd backend
./mvnw verify
```

Each service builds and tests on its own (`./mvnw verify` in `backend`, `services/audit-service` and
`services/notification-service`). CI runs all three in parallel. There are 138 tests: 99 in the core, 21 in the
audit service and 18 in the notification service. Integration tests run against a real PostgreSQL and a real
Kafka broker in Docker (Testcontainers), because locking, constraint and delivery behaviour cannot be verified
with in-memory fakes. Highlights:

- `ConcurrentTransferIntegrationTest` runs 100 HTTP clients at once against the real server: random
  transfers in both directions, 100 simultaneous withdrawals from one account (exactly 10 of 10,000 fit
  into 100,000), and an A→B / B→A storm. Afterwards it checks every ledger invariant with SQL, and that
  PostgreSQL's deadlock counter did not move.
- `IdempotencyApiIntegrationTest` covers replay, key reuse, a request arriving while the first is
  blocked, and 20 simultaneous double-submits that create exactly one transfer.
- `LedgerSchemaIntegrationTest` attacks the schema with raw SQL to prove the database-level guards.
- `OutboxRelayIntegrationTest` runs 4 competing relays over 200 interleaved events and checks per-transfer order
  in Kafka. A poison event only holds back its own transfer.
- `KafkaOutageIntegrationTest` freezes the broker, makes 20 payments (all succeed), then unfreezes it and finds
  all 40 events in Kafka, in order.
- `AuditLogIntegrationTest` edits, re-hashes and deletes audit rows as a superuser with triggers disabled,
  and checks that verification pinpoints each one.
- `IdempotentConsumerIntegrationTest` and `RetryAndDeadLetterIntegrationTest` cover 10 concurrent copies of one
  event (one acts), transient failures recovered from retry topics, and poison or unknown-version events parked
  on the dead-letter topic.

Several of these were checked by breaking the code on purpose: without the relay's ordering guard, the audit
chain lock or the `processed_events` check, the corresponding tests fail.

Locking benchmark (opt-in, results in [docs/benchmarks/locking.md](docs/benchmarks/locking.md)):

```bash
./mvnw test -Dtest=LockingBenchmarkTest -Dbenchmark=true
```

Kafka outage demo against the real stack. Start the infrastructure and all three services first:

```bash
scripts/demo-kafka-outage.sh
```

It stops Kafka, makes 5 transfers (each completes in about 200 ms), shows the events waiting in the outbox,
starts Kafka again, and then shows the outbox drained, the customer's SMS and the audit chain verified.

## Roadmap

- [x] **Phase 1 – Foundation:** infrastructure, account API, Flyway, Testcontainers, CI
- [x] **Phase 2 – Money core:** deposits, transfers, double-entry ledger, pessimistic locking, idempotency keys, reversals
- [x] **Phase 3 – Events:** transactional outbox, Kafka events, audit & notification consumers, DLQ
- [ ] **Phase 4 – Security:** JWT, RBAC, API keys, rate limiting
- [ ] **Phase 5 – Observability:** business metrics, Grafana dashboards, correlation IDs
- [ ] **Phase 6 – Frontend & polish:** React UI, k6 load tests, deployment

# Payment Processing & Ledger Platform

A banking-oriented payment platform: accounts, money transfers with a double-entry ledger,
idempotent APIs, concurrency-safe balance updates, and event-driven downstream processing.

> Status: **Phase 2 – Money core** done (deposits, transfers, reversals, double-entry ledger,
> pessimistic locking, idempotency keys). Next: Phase 3 – events.

## Architecture

```
React (TS) ──► API Gateway (JWT, rate limit, API key)
                    │
                    ▼
            Core Service (Spring Boot, modular monolith)
            ├── account    ✅
            ├── transfer   ✅ ─┐
            ├── ledger     ✅ ─┤ one DB transaction
            └── outbox        ─┘
                    │
              PostgreSQL ◄── Outbox relay ──► Kafka
                                                ├── Notification Service
                                                └── Audit Service (append-only)
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
```

| Service     | URL                                  | Notes                       |
|-------------|--------------------------------------|-----------------------------|
| Core API    | http://localhost:8080/api/v1         |                             |
| Metrics     | http://localhost:8080/actuator/prometheus |                        |
| PostgreSQL  | localhost:15432                      | payledger / payledger       |
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

## How the money stays correct

- **Double entry.** Every movement posts one DEBIT and one CREDIT of the same amount. Deposits debit a
  per-currency SYSTEM funding account, so every currency's balances always sum to zero.
- **Database-enforced invariants.** Customer balances have a `CHECK (balance >= 0)`. Ledger entries are
  append-only (triggers block UPDATE/DELETE/TRUNCATE). A deferred constraint trigger rejects any
  transfer whose entries do not balance at commit.
- **Pessimistic locking.** Both accounts are locked `FOR NO KEY UPDATE` in ascending id order under
  `READ COMMITTED`, with a bounded `lock_timeout`. Opposite transfers cannot deadlock.
- **Corrections are new entries.** A reversal posts compensating entries. Original entries never change.

## Testing

```bash
cd backend
./mvnw verify
```

84 tests. Integration tests run against a real PostgreSQL in Docker (Testcontainers), because locking and
constraint behaviour cannot be verified with an in-memory database. Highlights:

- `ConcurrentTransferIntegrationTest` runs 100 HTTP clients at once against the real server: random
  transfers in both directions, 100 simultaneous withdrawals from one account (exactly 10 of 10,000 fit
  into 100,000), and an A→B / B→A storm. Afterwards it checks every ledger invariant with SQL, and that
  PostgreSQL's deadlock counter did not move.
- `IdempotencyApiIntegrationTest` covers replay, key reuse, a request arriving while the first is
  blocked, and 20 simultaneous double-submits that create exactly one transfer.
- `LedgerSchemaIntegrationTest` attacks the schema with raw SQL to prove the database-level guards.

Locking benchmark (opt-in, results in [docs/benchmarks/locking.md](docs/benchmarks/locking.md)):

```bash
./mvnw test -Dtest=LockingBenchmarkTest -Dbenchmark=true
```

## Roadmap

- [x] **Phase 1 – Foundation:** infrastructure, account API, Flyway, Testcontainers, CI
- [x] **Phase 2 – Money core:** deposits, transfers, double-entry ledger, pessimistic locking, idempotency keys, reversals
- [ ] **Phase 3 – Events:** transactional outbox, Kafka events, audit & notification consumers, DLQ
- [ ] **Phase 4 – Security:** JWT, RBAC, API keys, rate limiting
- [ ] **Phase 5 – Observability:** business metrics, Grafana dashboards, correlation IDs
- [ ] **Phase 6 – Frontend & polish:** React UI, k6 load tests, deployment

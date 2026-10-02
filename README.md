# Payment Processing & Ledger Platform

A banking-oriented payment platform: accounts, money transfers with a double-entry ledger,
idempotent APIs, concurrency-safe balance updates, and event-driven downstream processing.

> Status: **Phase 1 – Foundation** (account management, infrastructure, CI).

## Architecture

```
React (TS) ──► API Gateway (JWT, rate limit, API key)
                    │
                    ▼
            Core Service (Spring Boot, modular monolith)
            ├── account    ✅
            ├── transfer   ─┐
            ├── ledger     ─┤ one DB transaction
            └── outbox     ─┘
                    │
              PostgreSQL ◄── Outbox relay ──► Kafka
                                                ├── Notification Service
                                                └── Audit Service (append-only)
            Redis: rate limiting, idempotency cache
            Prometheus + Grafana: metrics
```

Key design decisions are recorded in [docs/adr](docs/adr).

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

## API (Phase 1)

| Method | Path                                 | Description                          |
|--------|--------------------------------------|--------------------------------------|
| POST   | `/api/v1/accounts`                   | Open an account (`ownerId`, `currency`) |
| GET    | `/api/v1/accounts/{id}`              | Get account and balance              |
| GET    | `/api/v1/accounts?ownerId=`          | List an owner's accounts             |
| POST   | `/api/v1/accounts/{id}/freeze`       | ACTIVE → FROZEN                      |
| POST   | `/api/v1/accounts/{id}/unfreeze`     | FROZEN → ACTIVE                      |
| POST   | `/api/v1/accounts/{id}/close`        | → CLOSED (balance must be zero)      |

Balances are integers in the currency's minor unit. Errors follow RFC 9457 problem details with a
stable `code` field, e.g.:

```json
{
  "status": 422,
  "title": "Unprocessable Content",
  "detail": "Cannot freeze account 3f2a... in status FROZEN",
  "code": "INVALID_ACCOUNT_STATUS_TRANSITION"
}
```

## Testing

```bash
cd backend
./mvnw verify
```

Integration tests run against a real PostgreSQL in Docker (Testcontainers), because locking and
constraint behaviour cannot be verified with an in-memory database.

## Roadmap

- [x] **Phase 1 – Foundation:** infrastructure, account API, Flyway, Testcontainers, CI
- [ ] **Phase 2 – Money core:** transfers, double-entry ledger, pessimistic locking, idempotency keys, reversals
- [ ] **Phase 3 – Events:** transactional outbox, Kafka events, audit & notification consumers, DLQ
- [ ] **Phase 4 – Security:** JWT, RBAC, API keys, rate limiting
- [ ] **Phase 5 – Observability:** business metrics, Grafana dashboards, correlation IDs
- [ ] **Phase 6 – Frontend & polish:** React UI, k6 load tests, deployment

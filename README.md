# Payment Processing & Ledger Platform

A banking-oriented payment platform: accounts, money transfers with a double-entry ledger,
idempotent APIs, concurrency-safe balance updates, and event-driven downstream processing.

> Status: **Phase 4 – Security** done (ES256 access tokens with rotating refresh tokens, Argon2id passwords and
> lockout, role-based and object-level authorization, hashed API keys for the bank integration, rate limiting on
> Redis, security events in the audit trail). Next: Phase 5 – observability.

## Architecture

```
React (TS) ─────────┐  Bearer access token (ES256 JWT)
Partner bank ───────┤  X-API-Key (deposits only)
                    ▼
            Core Service (Spring Boot, modular monolith)
            ├── security   ✅ sign-in, tokens, JWKS, RBAC, API keys, rate limit ──► Redis (token buckets)
            ├── account    ✅
            ├── transfer   ✅ ─┐
            ├── ledger     ✅ ─┤ one DB transaction
            └── outbox     ✅ ─┘
                    │
              PostgreSQL ◄── Outbox relay ✅ ──► Kafka: payledger.transfers / .accounts / .security
                                                   ├── Notification Service ✅ (own DB)
                                                   └── Audit Service ✅ (own DB, hash chain)
                                      both verify tokens with the core's public keys (JWKS)
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
| [0010](docs/adr/0010-authentication-tokens.md) | ES256 access tokens, JWKS, rotating refresh tokens, Argon2id passwords and lockout |
| [0011](docs/adr/0011-authorization-and-api-keys.md) | Role-based and object-level authorization, API keys for machine clients |
| [0012](docs/adr/0012-rate-limiting.md) | Token-bucket rate limiting in Redis, failing open |

The event contract is documented in [docs/events.md](docs/events.md).

## Tech stack

Java 21 · Spring Boot 4.1 · Spring Security 7 (OAuth 2.0 resource server, Nimbus JOSE) · PostgreSQL 17 · Flyway ·
Kafka 4 (KRaft) · Redis + Bucket4j · Testcontainers · Prometheus · Grafana · Docker Compose · GitHub Actions

## Getting started

Prerequisites: JDK 21, Docker.

```bash
# 1. Start infrastructure
cd infra
docker compose up -d --wait

# 2. Run the core service. The password creates the first ADMIN (15+ characters, used only while no ADMIN exists).
cd ../backend
PAYLEDGER_ADMIN_PASSWORD='dev-only bootstrap passphrase' ./mvnw spring-boot:run

# 3. (Optional) Run the consumers, each in its own terminal
cd ../services/audit-service && ./mvnw spring-boot:run
cd ../services/notification-service && ./mvnw spring-boot:run
```

| Service     | URL                                  | Notes                       |
|-------------|--------------------------------------|-----------------------------|
| Core API    | http://localhost:8080/api/v1         | Sign in as `admin` with the password above |
| JWKS        | http://localhost:8080/.well-known/jwks.json | Public keys the other services verify tokens with |
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

Without `PAYLEDGER_JWT_SIGNING_KEY` (a private P-256 JWK), the core signs tokens with a random key generated at
startup and logs a warning: tokens then die with the process. Fine locally, not for more than one instance.

## API

Every endpoint except sign-up, sign-in, refresh, sign-out, the JWKS and the probes needs credentials: a bearer
access token for people, an `X-API-Key` for machine clients. "Own" means accounts whose owner is the caller.

### Authentication

| Method | Path                       | Access | Description |
|--------|----------------------------|--------|-------------|
| POST   | `/api/v1/auth/signup`      | anyone | Create a CUSTOMER (`username`, `password`) |
| POST   | `/api/v1/auth/login`       | anyone | Access token (5 min) + refresh token |
| POST   | `/api/v1/auth/refresh`     | anyone with a refresh token | New access token and new refresh token; the old one stops working |
| POST   | `/api/v1/auth/logout`      | anyone with a refresh token | End the session; always `204` |
| GET    | `/.well-known/jwks.json`   | anyone | Public signing keys (RFC 7517) |

```bash
curl -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username": "alice", "password": "correct horse battery staple"}'
# {"accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 300,
#  "refreshToken": "q3Vt…", "refreshExpiresIn": 900}
```

### Users and API keys

| Method | Path                            | Access | Description |
|--------|---------------------------------|--------|-------------|
| GET    | `/api/v1/users/me`              | any user | The signed-in user |
| POST   | `/api/v1/users`                 | ADMIN | Create a user with any role (staff) |
| GET    | `/api/v1/users/{id}`            | OPERATOR | Look a user up, including a lockout |
| POST   | `/api/v1/users/{id}/unlock`     | OPERATOR | Lift a sign-in lockout |
| POST   | `/api/v1/api-keys`              | ADMIN | Issue a key (`name`, `scopes`, optional `expiresAt`); the key is shown only in this response |
| GET    | `/api/v1/api-keys`, `/{id}`     | ADMIN | List keys (never the secret) |
| POST   | `/api/v1/api-keys/{id}/revoke`  | ADMIN | Revoke at once |

### Accounts

| Method | Path                                 | Access | Description |
|--------|--------------------------------------|--------|-------------|
| POST   | `/api/v1/accounts`                   | CUSTOMER | Open an account (`currency`); the owner is the caller |
| GET    | `/api/v1/accounts/{id}`              | own, OPERATOR | Account and balance |
| GET    | `/api/v1/accounts`                   | CUSTOMER | The caller's accounts |
| GET    | `/api/v1/accounts?ownerId=`          | OPERATOR | A customer's accounts |
| POST   | `/api/v1/accounts/{id}/freeze`       | OPERATOR | ACTIVE → FROZEN |
| POST   | `/api/v1/accounts/{id}/unfreeze`     | OPERATOR | FROZEN → ACTIVE |
| POST   | `/api/v1/accounts/{id}/close`        | own, OPERATOR | → CLOSED (balance must be zero) |

### Money movement

All three `POST`s require an `Idempotency-Key` header.

| Method | Path                                 | Access | Description |
|--------|--------------------------------------|--------|-------------|
| POST   | `/api/v1/deposits`                   | API key `deposits:write` | Credit a customer with money received from outside (bank top-up) |
| POST   | `/api/v1/transfers`                  | CUSTOMER, from an own account | Move money to any customer account |
| GET    | `/api/v1/transfers/{id}`             | either party, OPERATOR | A deposit, transfer or reversal, including rejected ones |
| POST   | `/api/v1/transfers/{id}/reversals`   | OPERATOR | Reverse a completed deposit or transfer (`reason`) |

```bash
curl -X POST localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
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

Security errors use the same format:

| Code | Status | Meaning |
|---|---|---|
| `AUTHENTICATION_REQUIRED` / `INVALID_TOKEN` / `INVALID_API_KEY` | 401 | No credentials, or bad ones |
| `INVALID_CREDENTIALS` | 401 | Wrong password or unknown username (indistinguishable on purpose) |
| `ACCOUNT_LOCKED` | 401 | 5 wrong passwords in a row; `Retry-After` says when the lock ends |
| `INVALID_REFRESH_TOKEN` / `REFRESH_TOKEN_REUSED` | 401 | Expired or ended session / a replayed token, which ends the session |
| `ACCESS_DENIED` | 403 | The caller's role does not allow this operation |
| `RESOURCE_NOT_FOUND` | 404 | Also for someone else's account or transfer, so ids cannot be probed |
| `RATE_LIMITED` | 429 | Too many requests; see `Retry-After` |

### Idempotency

Follows the IETF *Idempotency-Key* draft with Stripe-style semantics ([ADR 0005](docs/adr/0005-idempotency-keys.md)).
Keys are scoped to the caller: two clients picking the same key never see each other's responses.

| Retry with the same key | Response |
|---|---|
| Same request, completed | Original response replayed byte for byte, `Idempotent-Replayed: true` |
| Different request | `422 IDEMPOTENCY_KEY_REUSED` |
| First request still running | `409 IDEMPOTENCY_KEY_IN_PROGRESS` + `Retry-After` |

### Audit and notifications

| Method | Path                                         | Service | Access | Description |
|--------|----------------------------------------------|---------|--------|-------------|
| GET    | `/api/v1/audit-events?resourceId=`           | audit (8082) | AUDITOR | The trail of a transfer, account, user or API key, oldest first |
| GET    | `/api/v1/audit-events/verification`          | audit (8082) | AUDITOR | Re-hash the whole chain; reports the first altered or missing record |
| GET    | `/api/v1/notifications`                      | notification (8083) | CUSTOMER | The caller's messages, newest first |
| GET    | `/api/v1/notifications?recipientId=`         | notification (8083) | OPERATOR | A customer's messages |

## How access is controlled

| | |
|---|---|
| **Tokens** | 5-minute RFC 9068 access tokens signed with **ES256** (one of the algorithms FAPI 2.0 allows). The audit and notification services verify them with the core's public keys from `/.well-known/jwks.json` and cannot mint any. |
| **Sessions** | Opaque refresh tokens, stored as SHA-256, **rotated** on every use. A replayed refresh token **revokes the whole session** (RFC 9700 reuse detection). 15-minute idle timeout, 8-hour absolute lifetime. |
| **Passwords** | **Argon2id** with OWASP parameters; NIST SP 800-63B-4 policy (15–64 characters, no composition rules). The 5th wrong password in a row locks the user for 15 minutes, as Vietnamese banks do; the user row is locked while a password is checked, so parallel guesses cannot get past the count. |
| **Roles** | CUSTOMER, OPERATOR, AUDITOR, ADMIN. ADMIN inherits OPERATOR and AUDITOR but not CUSTOMER: no staff member can move a customer's money. Deposits only come from the bank's API key. |
| **Objects** | Customers reach only their own accounts. Someone else's account answers 404, like a missing one (OWASP API1); a transfer from it is refused before anything is locked or recorded. |
| **Deny by default** | Every controller method has a `@PreAuthorize` rule; `EndpointSecurityTest` fails the build if one does not. |
| **API keys** | GitHub-style `plk_…` keys with a checksum, shown once, stored as SHA-256, with scopes, expiry and revocation. |
| **Rate limits** | Token buckets in Redis per user (120/min), per API key (600/min) and per address for sign-in (20/min). **Fails open** when Redis is down, as Stripe advises. |
| **Audit** | Every event names its `actor`. Account freezes, sign-ins and failures (with the client IP), lockouts, revoked sessions, users and API keys go into the hash-chained audit trail (PCI DSS 10.2.1). |

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
`services/notification-service`). CI runs all three in parallel. There are 243 tests: 194 in the core, 26 in the
audit service and 23 in the notification service. Integration tests run against a real PostgreSQL, a real
Kafka broker and a real Redis in Docker (Testcontainers), because locking, constraint and delivery behaviour cannot
be verified with in-memory fakes. Requests go through the real security filters with real signed tokens.
Highlights:

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
- `AuthApiIntegrationTest` sends 30 password guesses at once: exactly 4 are rejected, the 5th locks the user out,
  and the other 25 are refused without checking the password. It also rejects expired, tampered, foreign-key,
  `alg: none`, wrong-audience and ID-token-typed JWTs.
- `RefreshTokenApiIntegrationTest` races 10 refreshes with one token (one wins, the race counts as reuse) and
  replays an exchanged token (the whole session ends).
- `AccessControlIntegrationTest` walks the role table and the object-level rules: someone else's account is 404, a
  transfer from it leaves no trace, an ADMIN cannot move customer money, only the bank's key can deposit.
- `RateLimitIntegrationTest` freezes Redis with `docker pause`: requests keep flowing, then limiting resumes.

Several of these were checked by breaking the code on purpose: without the relay's ordering guard, the audit
chain lock, the `processed_events` check or the row lock on sign-in, the corresponding tests fail.

Locking benchmark (opt-in, results in [docs/benchmarks/locking.md](docs/benchmarks/locking.md)):

```bash
./mvnw test -Dtest=LockingBenchmarkTest -Dbenchmark=true
```

Demos against the real stack. Start the infrastructure and all three services first (the core with
`PAYLEDGER_ADMIN_PASSWORD`, as above):

```bash
scripts/demo-kafka-outage.sh
scripts/demo-security.sh
```

The first stops Kafka, makes 5 transfers (each completes in about 200 ms), shows the events waiting in the outbox,
starts Kafka again, and then shows the outbox drained, the customer's SMS and the audit chain verified. The second
shows a customer failing to spend or read someone else's account, a lockout after 5 wrong passwords and an
operator unlocking it, a stolen refresh token ending the session, a burst of requests cut off with 429, and the
victim's security trail in the audit service.

## Roadmap

- [x] **Phase 1 – Foundation:** infrastructure, account API, Flyway, Testcontainers, CI
- [x] **Phase 2 – Money core:** deposits, transfers, double-entry ledger, pessimistic locking, idempotency keys, reversals
- [x] **Phase 3 – Events:** transactional outbox, Kafka events, audit & notification consumers, DLQ
- [x] **Phase 4 – Security:** JWT, refresh tokens, RBAC, API keys, rate limiting, security audit events
- [ ] **Phase 5 – Observability:** business metrics, Grafana dashboards, correlation IDs
- [ ] **Phase 6 – Frontend & polish:** React UI, k6 load tests, deployment

# Runbook

What to do when an alert from [`infra/prometheus/alerts.yml`](../infra/prometheus/alerts.yml) fires. Each alert links
to its section here. Dashboards: **PayLedger · Money movement** and **PayLedger · Events & platform** in Grafana.

## Finding one request

Every API response carries two ids:

| Header | What it is | Where to look it up |
|---|---|---|
| `X-Trace-Id` | Our W3C trace id | Jaeger (`/trace/<id>`): the request, the publication of its events, their consumption by audit and notification. Logs: `trace.id` field. Audit trail: the `traceparent` of each recorded event. |
| `x-fapi-interaction-id` | The caller's own id for the call (FAPI), or one we generated | Logs: `fapi.interaction_id`. Jaeger: tag `fapi.interaction_id` on the request span. |

A customer or the partner bank usually quotes one of them. Logs are JSON lines in Elastic Common Schema, so any log
search can filter on these fields.

## ServiceDown

**Means:** Prometheus cannot scrape the service. **Impact:** core down = no payments; audit or notification down =
events wait in Kafka (nothing is lost, see ConsumerLagHigh).

1. `curl <service>/actuator/health`. If it answers, the problem is between Prometheus and the service (network,
   port, `host.docker.internal` in local setups).
2. Otherwise look at the service's last log lines: failed migrations, database or Kafka unreachable at startup,
   out of memory.
3. Restart it. The core is stateless apart from PostgreSQL; consumers resume from their committed offsets.

## MoneyMovementErrors

**Means:** more than 5% of transfer and deposit requests end in 5xx. 422 rejections (insufficient funds…) are not
counted. **Impact:** customers cannot pay.

1. Which status? `503 LOCK_TIMEOUT` → see AccountLockTimeouts. `500` → an unexpected exception: find a failing
   request's trace in Jaeger (search service `payledger-core`, tag `error=true`) and its log lines by `trace.id`.
2. Check the database (connections on the **Core database connections** panel, PostgreSQL logs).
3. Clients may retry with the same `Idempotency-Key`: a request that failed with 5xx committed nothing.

## MoneyMovementSlow

**Means:** p99 from the start of a movement to its commit is above 1 s for 10 minutes.

1. Compare **Account lock wait**: if it explains the time, one account is hot (many movements on the same
   account, e.g. a merchant or the funding account). ADR 0004 lists the remedies (sub-accounts, batching).
2. Otherwise click an exemplar dot on **Movement time** to open a slow trace: is the time in the database, in
   security, or in the commit?
3. **Core database connections**: requests waiting for a connection mean the pool is too small or connections are
   held too long.

## AccountLockTimeouts

**Means:** movements gave up waiting for an account lock (`lock_timeout`, 3 s) and answered `503 LOCK_TIMEOUT`.
Clients retry with the same key, so nothing is lost, but customers wait.

1. Find the hot account: in PostgreSQL, `SELECT * FROM pg_locks l JOIN pg_stat_activity a USING (pid) WHERE NOT
   granted;` and the transaction holding the lock.
2. A long transaction holding a lock (a stuck session, a manual `psql` session) can be ended with
   `pg_terminate_backend(pid)`.
3. Sustained contention on one account is a design limit: see ADR 0004.

## DeadlockDetected

**Means:** PostgreSQL aborted a deadlock between money movements. Locks are always taken in ascending account id
order (ADR 0004), so this should be impossible: a code change broke the ordering. Affected requests got a 503 and
committed nothing.

1. PostgreSQL logs the two statements involved (`deadlock detected … Process … waits for …`).
2. Find the recent change that locks rows outside `AccountLocker` (or locks a transfer row after the accounts).
   `ConcurrentTransferIntegrationTest` should have caught it: check why it did not.
3. Roll back that change.

## OutboxStalled

**Means:** the oldest event waiting in the outbox is older than a minute. Payments still work, but the audit trail
and the customers' notifications fall behind.

1. **Outbox backlog** rising for all events: Kafka is down or unreachable. The relay logs
   `Published 0 of N outbox events…` with the cause. Events are published as soon as Kafka is back.
2. Backlog flat but old: one **poison event** holds back its own aggregate (later events of the same transfer wait
   behind it, to keep the order). Find it:
   `SELECT id, topic, event_type, attempts, last_error FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 10;`
   A high `attempts` with a `last_error` is the one. Fix the cause (topic missing, record too large) rather than
   deleting it: consumers expect every event.
3. No new attempts at all: the relay itself is stuck; restart the instance (any other instance takes over the work).

## AuditGap

**Means:** an event could not be recorded in the audit trail after its retries and was parked on
`<topic>-audit-dlt`. A missing audit record is a compliance incident.

1. The audit service logs `AUDIT GAP: event parked on …` with the exception. Malformed events and unknown schema
   versions go there directly; database errors only after ~80 s of retries.
2. Fix the cause (database, a schema version the audit service does not know yet: deploy the newer audit service).
3. Replay the parked events onto the original topic (Kafka UI → topic → message → "Produce to topic", or
   `kafka-console-consumer` / `kafka-console-producer`). Recording is idempotent by event id, so replaying an event
   twice is safe. Then check `GET /api/v1/audit-events/verification`.

## NotificationsDeadLettered

**Means:** customers did not get the balance-change message for some movements; the events were parked on
`payledger.transfers-notification-dlt`.

1. The notification service logs `Event parked on …` with the exception (SMS gateway errors, malformed events).
2. Fix the cause, then replay the events onto `payledger.transfers`: each event is notified at most once, so
   replaying is safe.

## ConsumerLagHigh

**Means:** a consumer group has more than 1000 messages waiting on a topic for 5 minutes. Measured by
kafka-exporter from committed offsets, so it fires even when the consumer is down (it then reports nothing itself).

1. Is the consumer running? (ServiceDown, **Events processed** at zero.)
2. Running but slow: **Events processed** below the publish rate. Look at its database and at retries (messages on
   `-retry-N` topics mean failures).
3. Scale out up to the number of partitions (3), one consumer thread per partition.

## RateLimiterFailingOpen

**Means:** Redis is unavailable or slower than 200 ms, so requests are served without rate limits (ADR 0012: a
broken limiter must not stop payments). Password guessing is still stopped by the account lockout, which lives in
PostgreSQL.

1. Check Redis (`redis-cli ping`, memory, network).
2. Limiting resumes on its own within 5 seconds of Redis answering again.

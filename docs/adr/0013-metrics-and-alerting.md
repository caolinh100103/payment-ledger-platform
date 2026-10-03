# ADR 0013: Business metrics, consumer lag from the broker, symptom-based alerts

**Status:** Accepted (Phase 5)

## Context

Until Phase 4, Prometheus scraped only what Spring Boot exposes by default: JVM, HTTP and connection pool metrics.
Those say whether the processes are healthy, not whether money moves: how many transfers completed or were
rejected, how long they took, whether accounts are contended, whether events reach Kafka and the consumers keep up.
A payments team is paged on those symptoms, and a dashboard that only shows CPU hides them.

## Decision

### Business metrics with Micrometer, scraped by Prometheus

| Metric | Type | Tags | Answers |
|---|---|---|---|
| `payledger_transfers_total` | counter | `type`, `status`, `failure_code` | Throughput and outcomes; rejections by reason |
| `payledger_transfers_amount_total` | counter | `type`, `currency` | Payment volume (minor units) |
| `payledger_transfer_duration_seconds` | histogram | `type`, `status` | Time from the start of a movement to its commit |
| `payledger_account_lock_wait_seconds` | histogram | | Contention on account rows (hot accounts, ADR 0004) |
| `payledger_lock_failures_total` | counter | `reason` = `lock_timeout` / `deadlock` | 503s, and deadlocks that ADR 0004 rules out |
| `payledger_idempotency_requests_total` | counter | `uri`, `outcome` | Replays, keys in progress, keys reused for another request |
| `payledger_outbox_pending`, `payledger_outbox_oldest_pending_age_seconds` | gauges | | The relay's backlog and how stale it is |
| `payledger_outbox_publish_attempts_total`, `payledger_outbox_delivery_delay_seconds` | counter, histogram | `topic`, `outcome` | Sends acknowledged or failed; write-to-acknowledgement delay |
| `payledger_events_consumed_total` | counter | `outcome` = `processed` / `duplicate` | Consumer throughput; redeliveries recognised by event id |
| `payledger_events_dead_lettered_total` | counter | `topic` | Events parked after their retries |
| `payledger_notifications_sent_total`, `payledger_rate_limit_requests_total` | counters | `channel`; `policy`, `outcome` | |

Rules that make these numbers trustworthy:

- **Counted after commit.** A transfer is counted in a transaction synchronization's `afterCommit`. A movement
  rolled back by a lock timeout or a lost idempotency lease never happened, so it is never counted. Consumers count
  after their transaction too; the SMS counter counts at the send, because a send cannot be rolled back.
- **Histograms, not client-side percentiles.** Each instance publishes buckets; `histogram_quantile()` over the sum
  gives the fleet's p95/p99. Percentiles computed per instance cannot be averaged. Bucket ranges are bounded
  (1 ms–10 s) to keep the series count down.
- **Bounded tags only.** Types, statuses, failure codes, currencies, route templates. Never an account, user or
  transfer id: each distinct value is a new time series, and Prometheus slows down and runs out of memory on
  high-cardinality labels. Ids go into logs and traces instead (ADR 0014).
- **Every known series starts at zero.** A Micrometer counter appears at its first increment, so Prometheus first
  sees it at 1 and `increase()` sees no change. The first rejection of a kind would be invisible, and an alert on
  "any increase" (AuditGap, DeadlockDetected) would never fire for a single event. All bounded label sets are
  registered at zero on startup (the idempotency routes on their first request).
- **Gauges from a schedule, not on scrape.** The outbox backlog is read every 10 seconds on its own scheduler
  thread, which keeps running while the relay thread waits on a dead broker. A failed read sets NaN rather than
  repeating a stale value. Ages and delays use the database clock, so the clocks of the app and database hosts are
  never compared.

### Consumer lag measured outside the consumers

`kafka-exporter` reads committed offsets from the broker and publishes `kafka_consumergroup_lag` per group, topic
and partition. The consumers' own client metric (`records-lag-max`) is useless exactly when it matters: a consumer
that is down or stuck in a rebalance reports nothing. This is why LinkedIn wrote Burrow and why lag is usually
monitored by a broker-side exporter. kafka-exporter reports `-1` for a partition without a committed offset, so
queries clamp at zero.

### Alerts on symptoms, each with a runbook and a unit test

Ten Prometheus rules in [`alerts.yml`](../../infra/prometheus/alerts.yml), following the Google SRE book: page on
what users or auditors feel (money endpoints failing or slow, events not reaching Kafka, an audit gap, consumers
falling behind, deadlocks), not on causes like CPU. Business rejections (422) are not errors. A backlog that drains
is fine; an *old* one pages. Each alert carries `severity` and a `runbook` link to [docs/runbook.md](../runbook.md).

`alerts.test.yml` is a `promtool test rules` suite run in CI: it checks that the outbox alert waits for its 2
minutes, a single deadlock or audit gap fires at once, retry topics do not count as lag, and 422s do not count as
errors.

### Dashboards as code

Two dashboards are provisioned from JSON files in `infra/grafana/dashboards` and cannot be edited in the UI (changes
go through pull requests): **Money movement** for the business view, **Events & platform** for operations. Colors
follow a validated, colorblind-safe palette, with fixed colors per series so a series never changes color when
another disappears. Latency panels show exemplars: a dot links to the trace of a request in that bucket (ADR 0014).

## Alternatives considered

- **Pushing metrics over OTLP** (the OpenTelemetry starter can). Prometheus scraping is the stack's existing
  convention, and pull makes "the target is down" a metric (`up`) for free. OTLP metric export is disabled.
- **Grafana-managed alerts.** Prometheus rules live next to the metrics, can be unit-tested with `promtool`, and do
  not depend on Grafana being up.
- **Burrow or KMinion for lag.** Both work with Kafka 4 (checked); kafka-exporter is the most widely deployed and
  has standard dashboards.
- **SLO burn-rate alerts** (Google SRE workbook). The right next step once there is real traffic to set objectives
  from; with demo traffic, threshold alerts are easier to reason about.

## Consequences

- Each new outcome (a new failure code, topic or route) must be added to the zero-initialised set, or its first
  occurrence is invisible to `increase()`.
- Volume counters are doubles: exact up to 2^53 minor units, plenty for dashboards, never a substitute for the ledger.
- Alertmanager is not part of the local stack: alerts are visible in Prometheus and on the dashboards, but nobody is
  paged. Routing (PagerDuty, Slack) is a deployment concern.

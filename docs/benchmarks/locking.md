# Benchmark: pessimistic vs optimistic locking for transfers

**Date:** 2026-10-03 · **Decision it informs:** [ADR 0004](../adr/0004-pessimistic-row-locking-for-transfers.md)

## Question

A transfer reads two balances, checks the rules and writes both balances back. Two ways to stop
concurrent transfers from overwriting each other:

- **Pessimistic:** `SELECT ... FOR NO KEY UPDATE` both account rows (ascending id order) before reading
  them. Conflicting transfers wait in line. This is the production strategy.
- **Optimistic:** read without locks; the `@Version` check on the balance `UPDATE` at commit detects a
  concurrent change; the whole transaction is retried with exponential backoff and full jitter
  (max 20 attempts).

Which one gives better throughput and tail latency, and at what level of contention?

## Setup

| | |
|---|---|
| Machine | Intel Core i7-11800H (8 cores / 16 threads), 16 GB RAM, Windows 11 |
| Database | PostgreSQL 17.11 in Docker (Testcontainers), default configuration |
| Application | Spring Boot 4.1, Java 21, HikariCP default pool (10 connections) |
| Load | 32 threads × 100 transfers = 3,200 transfers per run, after a warm-up run |
| Level | Service layer (`TransferService.transfer`), so HTTP and idempotency do not blur the comparison |

Every transfer in the benchmark succeeds on its own (accounts are funded with far more than they send),
so any difference comes from conflicts only. The ledger invariants are checked after the runs.

Reproduce:

```bash
cd backend
./mvnw test -Dtest=LockingBenchmarkTest -Dbenchmark=true
cat target/benchmark/locking.md
```

## Results

Latency is per logical transfer, including retries and backoff. "Gave up" means the transfer was still
conflicting after 20 attempts: the client would see an error.

| Contention | Strategy | Throughput (tx/s) | p50 (ms) | p95 (ms) | p99 (ms) | Retries | Gave up |
|---|---|---:|---:|---:|---:|---:|---:|
| Low: 1,000 accounts, random pairs | PESSIMISTIC | 1458 | 20.5 | 38.8 | 56.1 | 0 | 0 |
| Low: 1,000 accounts, random pairs | OPTIMISTIC | 1667 | 18.0 | 34.1 | 47.9 | 99 | 0 |
| Medium: 20 accounts, random pairs | PESSIMISTIC | 979 | 30.6 | 55.3 | 71.0 | 0 | 0 |
| Medium: 20 accounts, random pairs | OPTIMISTIC | 712 | 26.1 | 127.3 | 226.0 | 4146 | 0 |
| High: 32 payers → 1 merchant | PESSIMISTIC | 282 | 108.0 | 149.4 | 180.4 | 0 | 0 |
| High: 32 payers → 1 merchant | OPTIMISTIC | 183 | 58.1 | 343.8 | 433.2 | 22037 | 330 |

A first run on the same machine was within 12% on every throughput figure, and showed the same
ranking in every scenario.

## Reading the numbers

- **Low contention:** optimistic is about 14% faster. Conflicts are rare (99 retries in 3,200 transfers),
  and it saves the lock round-trip.
- **Medium contention:** pessimistic delivers 37% more throughput. Optimistic keeps a slightly lower median
  but its p99 is three times worse: every conflict throws away a whole transaction (two inserts, two
  updates) and backs off.
- **High contention (hot account):** pessimistic delivers 54% more throughput with a tight tail. Optimistic
  degenerates into a retry storm: about seven retries per transfer, and about 10% of payments fail
  outright even after 20 attempts. In a payment system that means a customer sees an error for a
  payment that would have succeeded if it had simply waited.
- The optimistic median looks better at high contention only because the lucky transfers that commit
  first are fast. The cost moves to the tail and to the failures.

## Conclusion

Use pessimistic row locks for balance changes. Real payment traffic is skewed: popular merchants,
payroll accounts and the platform's own SYSTEM accounts are hot spots. That is exactly where
optimistic locking collapses. The gain at low contention is small and does not justify failed payments
under load.

Optimistic locking (`@Version`) stays in place for low-contention updates such as account status
changes, and as a second line of defence on balances.

## Caveats

- One machine, Docker on Windows, small connection pool. Absolute numbers are not production numbers; the
  ranking between strategies is what matters.
- The hot-merchant result is also an upper bound for the current design: every transfer to that account
  queues on one row. [ADR 0004](../adr/0004-pessimistic-row-locking-for-transfers.md) lists how
  real ledgers relieve hot accounts.

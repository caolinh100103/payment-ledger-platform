# ADR 0001: Start as a modular monolith

**Status:** Accepted

## Context

The target architecture has several services (payment, ledger, notification, audit). Splitting them
from day one adds network calls, distributed transactions and deployment overhead before the core
money logic is proven correct.

## Decision

Build the core (accounts, transfers, ledger, outbox) as one Spring Boot application with strict
package boundaries. Only side-effect consumers that don't need strong consistency (notification,
audit) become separate services, fed by Kafka.

## Consequences

- Transfers and ledger entries can share one ACID transaction (see ADR 0003).
- Module boundaries must be kept by discipline; a module may only call another through its service API.
- Extracting a module later requires replacing in-process calls with events or HTTP.

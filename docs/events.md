# Event catalog

Events published by the core service. This page is the contract between the core service and its
consumers ([audit-service](../services/audit-service), [notification-service](../services/notification-service)).

## Transport

| | |
|---|---|
| Topic | `payledger.transfers` |
| Message key | The transfer id. Every event of one transfer lands on the same partition, in order. |
| Message value | A [CloudEvents 1.0](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md) envelope as JSON (structured content mode) |
| `content-type` header | `application/cloudevents+json; charset=UTF-8` ([Kafka protocol binding](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/kafka-protocol-binding.md)) |
| Delivery | **At least once.** The same event can arrive more than once; deduplicate on `id`. |
| Ordering | Guaranteed per transfer, not across transfers. |

Events are written to an outbox table in the same database transaction as the change they describe, then
relayed to Kafka ([ADR 0003](adr/0003-synchronous-ledger-with-outbox.md), [ADR 0007](adr/0007-outbox-relay.md)).
An event is published if and only if its change committed.

## Envelope

| Attribute | Example | Notes |
|---|---|---|
| `specversion` | `1.0` | |
| `id` | `5c0d…` | UUID, unique per event. The deduplication key. |
| `source` | `/payledger/core` | |
| `type` | `com.payledger.transfer.completed` | See below |
| `subject` | `9b0e…` | The transfer id (same as the Kafka key) |
| `time` | `2026-10-03T08:15:30.123456Z` | When the change happened |
| `datacontenttype` | `application/json` | |
| `schemaversion` | `1` | Extension attribute: version of `data` for this `type` |
| `actor` | `anonymous` | Extension attribute: who caused it. Phase 4 sets the authenticated user. |
| `data` | `{…}` | Snapshot of the transfer, see below |

## Event types

Every transfer emits `created`, then exactly one of `completed` or `failed`. A completed transfer that is later
undone also emits `reversed`. The reversal is itself a transfer of type `REVERSAL`, with its own
`created` → `completed` / `failed` events.

| `type` | When | `data.status` | Balances in `data` |
|---|---|---|---|
| `com.payledger.transfer.created` | The transfer was requested and both accounts exist | `PENDING` | no |
| `com.payledger.transfer.completed` | Money moved | `COMPLETED` | yes |
| `com.payledger.transfer.failed` | A business rule rejected it; no money moved | `FAILED` | no |
| `com.payledger.transfer.reversed` | A REVERSAL undid this transfer | `REVERSED` | no (they are in the reversal's `completed`) |

A request rejected before a transfer exists (unknown account, invalid body) emits nothing.

## `data` (schema version 1)

| Field | Type | Notes |
|---|---|---|
| `transferId` | UUID | |
| `type` | `DEPOSIT` / `TRANSFER` / `REVERSAL` | |
| `status` | `PENDING` / `COMPLETED` / `FAILED` / `REVERSED` | Status at the moment of the event |
| `amount` | integer | Minor unit of `currency` ([ADR 0002](adr/0002-money-as-minor-units.md)) |
| `currency` | ISO 4217 | |
| `description` | string or null | Up to 140 characters |
| `sourceAccount`, `destinationAccount` | object | `accountId`, `ownerId`, `accountType` (`CUSTOMER` / `SYSTEM`), `balanceAfter` |
| `…balanceAfter` | integer or null | The balance right after this event moved money. Set only on `completed`. |
| `failureCode`, `failureReason` | string or null | Set on `failed`, e.g. `INSUFFICIENT_FUNDS` |
| `reversalOf` | UUID or null | On a `REVERSAL`: the transfer it undoes |
| `reversedBy` | UUID or null | On `reversed`: the `REVERSAL` transfer that undid this one |
| `createdAt` | timestamp | When the transfer was requested |

Every field is always present; fields that do not apply are `null`.

## Versioning rules

- **Adding** a field is backward compatible: `schemaversion` stays the same. Consumers must ignore fields
  they do not know (tolerant reader).
- **Removing, renaming or changing the meaning** of a field is a breaking change: `schemaversion` is bumped,
  and the producer publishes both versions until every consumer has migrated.
- Consumers reject a `schemaversion` they do not support. The message goes to the consumer's dead-letter
  topic for a human to look at. It is not skipped silently.

## Example

```json
{
  "specversion": "1.0",
  "id": "5c0d6f6e-3b8e-4b9a-9d4f-2f1a7c3e8b10",
  "source": "/payledger/core",
  "type": "com.payledger.transfer.completed",
  "subject": "9b0e2c4d-7a61-4f0e-b8a3-1d2c3e4f5a6b",
  "time": "2026-10-03T08:15:30.123456Z",
  "datacontenttype": "application/json",
  "schemaversion": 1,
  "actor": "anonymous",
  "data": {
    "transferId": "9b0e2c4d-7a61-4f0e-b8a3-1d2c3e4f5a6b",
    "type": "TRANSFER",
    "status": "COMPLETED",
    "amount": 250000,
    "currency": "VND",
    "description": "Rent October",
    "sourceAccount": {
      "accountId": "3f2a9c1e-…",
      "ownerId": "alice",
      "accountType": "CUSTOMER",
      "balanceAfter": 750000
    },
    "destinationAccount": {
      "accountId": "7d4b1e2f-…",
      "ownerId": "bob",
      "accountType": "CUSTOMER",
      "balanceAfter": 250000
    },
    "failureCode": null,
    "failureReason": null,
    "reversalOf": null,
    "reversedBy": null,
    "createdAt": "2026-10-03T08:15:30.120001Z"
  }
}
```

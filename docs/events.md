# Event catalog

Events published by the core service. This page is the contract between the core service and its
consumers ([audit-service](../services/audit-service), [notification-service](../services/notification-service)).

## Transport

| Topic | Events | Message key | Consumers |
|---|---|---|---|
| `payledger.transfers` | Money movements | Transfer id | audit, notification |
| `payledger.accounts` | Account lifecycle | Account id | audit |
| `payledger.security` | Users, sign-ins, sessions, API keys | User id or API key id | audit |

| | |
|---|---|
| Message key | The aggregate id. Every event of one aggregate lands on the same partition, in order. |
| Message value | A [CloudEvents 1.0](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md) envelope as JSON (structured content mode) |
| `content-type` header | `application/cloudevents+json; charset=UTF-8` ([Kafka protocol binding](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/kafka-protocol-binding.md)) |
| Delivery | **At least once.** The same event can arrive more than once; deduplicate on `id`. |
| Ordering | Guaranteed per aggregate (transfer, account, user, API key), not across aggregates. |
| `traceparent` header | W3C trace context of the relay's publish span, a child of the event's own `traceparent` attribute. Consumers continue the trace from it ([ADR 0014](adr/0014-trace-context-and-structured-logs.md)). |

Events are written to an outbox table in the same database transaction as the change they describe, then
relayed to Kafka ([ADR 0003](adr/0003-synchronous-ledger-with-outbox.md), [ADR 0007](adr/0007-polling-outbox-relay.md)).
An event is published if and only if its change committed.

## Envelope

| Attribute | Example | Notes |
|---|---|---|
| `specversion` | `1.0` | |
| `id` | `5c0d…` | UUID, unique per event. The deduplication key. |
| `source` | `/payledger/core` | |
| `type` | `com.payledger.transfer.completed` | See below |
| `subject` | `9b0e…` | The aggregate id (same as the Kafka key) |
| `time` | `2026-10-03T08:15:30.123456Z` | When the change happened |
| `datacontenttype` | `application/json` | |
| `schemaversion` | `1` | Extension attribute: version of `data` for this `type` |
| `actor` | `user:3f2a…` | Extension attribute: who caused it, see below |
| `traceparent` | `00-62f0…34e2-dd29…f55e-03` | [Distributed Tracing extension](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/extensions/distributed-tracing.md): the W3C trace context of the request that caused the event. Absent if nothing was traced. |
| `tracestate` | | Same extension, only when the trace carries vendor state |
| `data` | `{…}` | Snapshot of the aggregate, see below |

### `actor`

| Value | Who |
|---|---|
| `user:<user id>` | A person signed in with an access token: the customer who paid, the operator who froze an account |
| `apikey:<key id>` | A machine client, e.g. the partner bank integration reporting a deposit |
| `anonymous` | A request without credentials: a sign-up, a failed sign-in |
| `system` | The platform's own decision, e.g. revoking a session whose refresh token was replayed |

Transfers recorded before Phase 4 carry `anonymous`.

## Transfer events (`payledger.transfers`)

Every transfer emits `created`, then exactly one of `completed` or `failed`. A completed transfer that is later
undone also emits `reversed`. The reversal is itself a transfer of type `REVERSAL`, with its own
`created` → `completed` / `failed` events.

| `type` | When | `data.status` | Balances in `data` |
|---|---|---|---|
| `com.payledger.transfer.created` | The transfer was requested and both accounts exist | `PENDING` | no |
| `com.payledger.transfer.completed` | Money moved | `COMPLETED` | yes |
| `com.payledger.transfer.failed` | A business rule rejected it; no money moved | `FAILED` | no |
| `com.payledger.transfer.reversed` | A REVERSAL undid this transfer | `REVERSED` | no (they are in the reversal's `completed`) |

A request rejected before a transfer exists (unknown account, someone else's source account, invalid body) emits
nothing. The `actor` is whoever asked for the transfer (stored as `transfers.initiated_by`); on `reversed` it is
whoever reversed it.

### `data` (schema version 1)

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

## Account events (`payledger.accounts`)

| `type` | When | Typical `actor` |
|---|---|---|
| `com.payledger.account.opened` | A customer opened an account | the customer |
| `com.payledger.account.frozen` | An operator froze it (e.g. suspected fraud) | an operator |
| `com.payledger.account.unfrozen` | An operator unfroze it | an operator |
| `com.payledger.account.closed` | It was closed (balance zero) | the customer or an operator |

`data` (schema version 1): `accountId`, `ownerId`, `currency`, `accountType`, `status` after the change. Balances
are not included; they belong to transfer events.

## Security events (`payledger.security`)

What PCI DSS requirement 10.2.1 asks an audit log to capture: access attempts, valid or not, creation of users and
changes to credentials, and administrators' actions.

| `type` | When | `actor` |
|---|---|---|
| `com.payledger.user.created` | Sign-up, or an ADMIN created a user | `anonymous` / the admin |
| `com.payledger.user.signed_in` | Correct password | the user |
| `com.payledger.user.sign_in_failed` | Wrong password (`reason: WRONG_PASSWORD`), or any attempt while locked out (`LOCKED_OUT`) | `anonymous` |
| `com.payledger.user.unlocked` | An operator lifted a lockout | the operator |
| `com.payledger.user.session_revoked` | Sign-out (`reason: LOGOUT`) or a replayed refresh token (`REFRESH_TOKEN_REUSE`) | the user / `system` |
| `com.payledger.apikey.created` | An ADMIN issued an API key | the admin |
| `com.payledger.apikey.revoked` | An ADMIN revoked it | the admin |

`data` of `user.*` (schema version 1): `userId`, `username`, `role`, `clientIp`, `failedLoginAttempts`,
`lockedUntil` (set once the attempt that reached the limit locked the user out), `sessionId`, `reason`. Fields that
do not apply are `null`. Passwords and tokens never appear.

`data` of `apikey.*` (schema version 1): `apiKeyId`, `name`, `prefix` (the first 12 characters, e.g.
`plk_6Hq2k9Xa`), `scopes`, `expiresAt`. Never the key itself.

A failed sign-in for a username that does not exist is not published: there is no user to attach it to, and the
text may be a password typed into the wrong field.

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
  "actor": "user:8c41f0d2-5e3a-4b7c-9f21-6a0d3e7b9c55",
  "data": {
    "transferId": "9b0e2c4d-7a61-4f0e-b8a3-1d2c3e4f5a6b",
    "type": "TRANSFER",
    "status": "COMPLETED",
    "amount": 250000,
    "currency": "VND",
    "description": "Rent October",
    "sourceAccount": {
      "accountId": "3f2a9c1e-…",
      "ownerId": "8c41f0d2-5e3a-4b7c-9f21-6a0d3e7b9c55",
      "accountType": "CUSTOMER",
      "balanceAfter": 750000
    },
    "destinationAccount": {
      "accountId": "7d4b1e2f-…",
      "ownerId": "1e9a7b3c-0d24-4f6e-8a51-c2b7d9e04f13",
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

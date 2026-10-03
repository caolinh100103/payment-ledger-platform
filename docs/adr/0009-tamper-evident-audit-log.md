# ADR 0009: Tamper-evident audit log in its own service

**Status:** Accepted (Phase 3)

## Context

A payment platform must be able to show, long after the fact, who did what to which money and when, and
prove that this record has not been edited since. PCI DSS v4.0 requirement 10.3 asks for audit logs to be
protected from destruction and unauthorised modification. Banking regulators ask the same (in Vietnam,
Circular 09/2020/TT-NHNN on information system security).

The threats differ in strength:

| Who | Can | Defence |
|---|---|---|
| A bug or a compromised application | Run any SQL the app's role allows | Database privileges |
| An operator or migration with owner rights | Run `UPDATE` / `DELETE` by mistake | Triggers |
| A superuser (DBA, attacker with root) | Disable triggers, rewrite rows | Detection only: a hash chain |

Nothing inside one database stops a superuser. The best we can do is make tampering **evident**.

## Decision

The audit trail is a separate service ([services/audit-service](../../services/audit-service)) with **its own
database**. It consumes every domain event from Kafka and appends it to `audit_events`. The core service has no
credentials for it, so a compromised core cannot rewrite history.

### Three layers

1. **Least privilege.** Flyway migrates as the owner `audit_owner`. The application connects as `audit_app`,
   which is granted only `SELECT` and `INSERT` on `audit_events`.
2. **Append-only triggers.** `UPDATE`, `DELETE` and `TRUNCATE` raise an error for every role, the owner
   included, the same as for `ledger_entries` in the core.
3. **Hash chain.** Each record stores
   `hash = SHA-256(seq, event_id, actor, action, resource_id, occurred_at, source, payload, recorded_at, prev_hash)`,
   where `prev_hash` is the previous record's `hash` (64 zeros for the first record). Fields are
   length-prefixed before hashing, so moving characters from one field to the next changes the hash.
   `GET /api/v1/audit-events/verification` recomputes the chain and reports the first broken record:

| Tampering | Detected as |
|---|---|
| A field of record *n* edited | *n*: content does not match its hash |
| A field edited and *n*'s hash recomputed | *n+1*: `prev_hash` does not match |
| Record *n* deleted | *n*: missing (`seq` is gapless: 1, 2, 3, …) |

`AuditLogIntegrationTest` performs each of these as a superuser with `session_replication_role = replica`
(triggers off) and checks the verification result.

### Recording

- **One chain, serialised appends.** A transaction-scoped advisory lock (`pg_advisory_xact_lock`) lets exactly
  one consumer read the head and link to it at a time. Without it, two consumers link to the same head: the
  concurrency test then fails with a duplicate `seq`.
- **Idempotent.** Kafka delivers at least once. The CloudEvents `id` is unique in `audit_events`, and a
  redelivered event is recognised under the lock and skipped. The audit row itself is the "processed" marker,
  so no separate `processed_events` table is needed here.
- **Verbatim.** The event is stored as `TEXT` exactly as received. `JSONB` would reorder keys and change the
  bytes that were hashed. The service does not interpret `data`, so it records any event type and any
  `schemaversion`.
- Kafka offsets are committed per record, after the database commit (`ack-mode: record`).

## Alternatives considered

- **Hash computed by a database trigger.** This also enforces the chain for rows written outside the app. But
  timestamp-to-text conversion in SQL depends on session settings (`TimeZone`, `DateStyle`), which makes the
  hash fragile. In Java it is explicit and unit tested.
- **Merkle tree** (Trillian, Certificate Transparency). This gives compact proofs that a single record is
  included, and proofs that one log state extends another, without re-reading everything. It is worth it for
  public, very large logs. A linear chain is simpler and sufficient here.
- **Managed ledger databases.** Amazon QLDB offered exactly this, and AWS has retired it. Depending on a vendor
  for a core compliance property is a risk in itself.
- **WORM storage** (S3 Object Lock in compliance mode) for archived log segments. This complements the chain
  for long-term retention. It is not a replacement for a queryable store.
- **One chain per Kafka partition.** There would be no global lock, so throughput scales with partitions, but
  verification and ordering become per partition. This is the path to take if a single chain becomes a
  bottleneck.

## Consequences

- **Truncating the tail is not detectable from inside the database.** Someone with superuser rights can delete
  the last *k* records, or rewrite the tail and recompute every hash after it. The verification response
  includes `headSeq` and `headHash` so they can be **anchored outside**: recorded periodically in another system,
  signed, or published. A later head that does not extend the anchored one proves tampering. Automating this
  checkpoint is future work.
- Throughput is bounded by one lock holder at a time: one short insert transaction per event. That is
  thousands of events per second, far above the expected audit volume.
- The chain order is the order of recording. With non-blocking retries (ADR 0008) it can differ from
  `occurred_at`, which is stored separately.
- The log contains personal data (owner ids, descriptions). Immutability conflicts with erasure rights (GDPR,
  Vietnam's Decree 13/2023/ND-CP). The usual answer is crypto-shredding: encrypt personal fields with a
  per-subject key and delete the key. This is not implemented yet.

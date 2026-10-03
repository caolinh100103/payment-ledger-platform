# ADR 0011: Role-based and object-level authorization, API keys for machine clients

**Status:** Accepted (Phase 4)

## Context

Once callers are authenticated ([ADR 0010](0010-authentication-tokens.md)), each request needs two answers: may this
kind of caller use this endpoint at all (function level), and may this caller touch this particular account or
transfer (object level)? The OWASP API Security Top 10 puts the second at number one (API1, Broken Object Level
Authorization) and the first at number five (API5). Besides people, the platform has a machine client: the partner
bank integration that reports money arriving (the top-up webhook).

## Decision

### Roles

| Role | May |
|---|---|
| `CUSTOMER` | Open accounts for themselves; see and close their own accounts; transfer from them to any customer account; see transfers they are a party to |
| `OPERATOR` | Look up any customer's accounts, transfers and users; freeze and unfreeze accounts; reverse transfers; unlock users |
| `AUDITOR` | Read the audit trail (audit service) |
| `ADMIN` | Everything OPERATOR and AUDITOR may, plus manage users and API keys |
| API key `deposits:write` | Report deposits, nothing else |

`ADMIN` implies `OPERATOR` and `AUDITOR` through Spring Security's `RoleHierarchy`, but **not `CUSTOMER`**:
segregation of duties. Moving a customer's money takes that customer's own credentials, so no staff member, however
senior, can pay themselves from someone else's account (`neitherAnAdminNorAnOperatorCanMoveACustomersMoney`).

**Deposits only come from the bank's API key**, not from any person. A deposit creates customer money backed by the
SYSTEM funding account ([ADR 0006](0006-double-entry-ledger-model.md)); only the bank knows the money really
arrived. An operator who could deposit could mint money for an accomplice.

### Where the rules live

- **Function level:** a `@PreAuthorize` rule on every controller method, next to the code it protects. The filter
  chain only separates public endpoints (sign-in, JWKS, probes) from the rest. `EndpointSecurityTest` walks every
  handler method and fails the build if one has no rule: deny by default, even for an endpoint added in a hurry.
- **Object level:** in the services, which receive the caller as an `Actor` (`user:<id>`, `apikey:<id>`). A customer
  passes only for an account whose `owner_id` is their user id.
- **Someone else's account or transfer answers 404**, exactly like one that does not exist, as GitHub does for a
  private repository. A 403 would confirm that the id exists. Listing someone else's accounts answers 403, since a
  list reveals nothing about one id.
- **A transfer from someone else's account is refused before anything happens:** no row lock, no FAILED transfer in
  the victim's history, no event. The owner is read with a projection (`select a.ownerId ...`), not the entity, so the
  later `FOR NO KEY UPDATE` still returns a fresh row ([ADR 0004](0004-pessimistic-row-locking-for-transfers.md)).
  Ownership never changes (`owner_id` is not updatable), so checking it before the lock is safe.

### Consequences for existing features

- **The owner comes from the token.** `POST /accounts` takes only a currency; an `ownerId` in the body is ignored.
- **Who initiated a transfer is recorded** in `transfers.initiated_by` (the "maker" of a core banking transaction)
  and carried as the CloudEvents `actor` to the audit trail. On a reversal it is the operator.
- **Idempotency keys are scoped to the caller** (`idempotency_keys.scope = user:<id>` or `apikey:<id>`), as at
  Stripe: two clients that pick the same key never see each other's responses.
- **Privileged actions are audited** ([ADR 0009](0009-tamper-evident-audit-log.md), [events](../events.md)): account
  freezes, unlocks, user and API key creation, sign-ins and failed sign-ins.

### API keys

| Choice | Why |
|---|---|
| Format `plk_` + 32 random base62 characters + 6-character CRC32 checksum | GitHub's token format: the prefix lets secret scanners and log filters recognise a leaked key; the checksum rejects a mistyped or invented key without a database lookup. About 190 random bits. |
| Shown once; only SHA-256 stored, plus a 12-character display prefix | As GitHub and Stripe. A fast hash is enough for a secret that cannot be guessed, and authentication is one index lookup. |
| Scopes as OAuth scope strings (`deposits:write`), granted as `SCOPE_*` authorities | Least privilege: a leaked top-up key cannot read customer data. |
| Sent in `X-API-Key`, not `Authorization` | Keeps the two credential types apart; a request with a bad key gets 401 at once rather than being treated as anonymous. |
| Optional expiry, revocation that keeps the row, `last_used_at` updated at most once a minute | PCI DSS asks for system credentials to be rotated; the audit trail must still resolve an old key id; a busy client should not turn every request into a write. |

## Alternatives considered

- **URL rules in the filter chain only.** One place to read, but far from the code, and nothing stops a new endpoint
  from being forgotten. Method rules plus the build-breaking test cover both.
- **403 for someone else's account.** Clearer to a confused user, but it confirms the account exists. Account ids are
  random UUIDs, so this matters less than with sequential ids; 404 is still the safer default.
- **Attribute-based access control (a policy engine such as OPA).** Worth it with many resource types and
  conditions; four roles and one ownership rule do not need it yet.
- **mTLS for the bank integration.** Real bank-to-bank links often use mutual TLS (and FAPI sender-constrained
  tokens). An API key keeps the demo self-contained; mTLS belongs at the gateway.

## Not done yet

- **Maker-checker** (four-eyes) for reversals above a threshold, as banks require for manual corrections.
- **Transaction limits** per customer (the State Bank of Vietnam's e-wallet caps), checked as business rules.
- Customer-facing name inquiry ("Napas" lookup of the beneficiary name before a transfer).
- A shared security library: the three services each carry ~80 lines of the same resource-server configuration.

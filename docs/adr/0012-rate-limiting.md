# ADR 0012: Rate limiting with token buckets in Redis, failing open

**Status:** Accepted (Phase 4)

## Context

Without limits, one client can flood the API (a buggy retry loop, a scraper) and an attacker can try leaked
credentials at full speed (credential stuffing). The account lockout ([ADR 0010](0010-authentication-tokens.md))
stops guessing one user's password, not trying one password on many users. The core runs as several instances, so a
per-instance counter would multiply the real limit by the number of instances.

## Decision

A **token bucket per caller, kept in Redis** with Bucket4j, checked by a servlet filter after authentication and
before authorization. This is Stripe's "request rate limiter", which also uses token buckets on Redis.

| Caller | Key | Default bucket |
|---|---|---|
| Signed-in user | `user:<id>` | 120, refilled at 120 a minute |
| API key | `apikey:<id>` | 600 a minute (a bank sends webhooks in bursts after a settlement batch) |
| No credentials (sign-in, sign-up, refresh) | `ip:<address>` | 20 a minute |

A bucket holds up to its capacity and refills continuously; each request takes one token. Short bursts pass and a
sustained flood is cut to the refill rate. Bucket4j updates the bucket with a compare-and-swap in Redis, so
concurrent requests on different instances never both take the last token. A bucket's key expires once it would be
full again, so idle clients cost no memory.

**Responses.** Every limited response carries `X-RateLimit-Limit` and `X-RateLimit-Remaining` (GitHub's headers). A
rejected request gets `429 Too Many Requests` (RFC 6585) with `Retry-After` in seconds and the problem code
`RATE_LIMITED`. Probes, Prometheus and the JWKS are not limited, so monitoring and the other services never get
locked out.

### Fail open

Following Stripe's advice, **a broken limiter must not break the API**: if Redis is down, slow or misconfigured,
requests go through unlimited.

- Redis commands time out after **200 ms** (Lettuce timeout options, and commands are rejected at once while
  disconnected instead of queueing).
- After a failure, Redis is **not asked again for 5 seconds**, so an outage costs one timeout, not one per request.
- The client connects lazily, so the core starts while Redis is down, and the Redis health indicator is disabled:
  Redis down must not take instances out of the load balancer.
- `payledger_rate_limit_requests_total{policy, outcome}` counts `allowed`, `rejected` and `bypassed`, so failing open
  is visible on a dashboard (Phase 5).

`failsOpenWhileRedisIsDownAndRecoversAfterwards` freezes Redis with `docker pause`: requests keep being served in
under a second each, and limiting resumes with the bucket state intact once Redis is back.

## Alternatives considered

- **Fail closed.** Safer against abuse, but turns a Redis outage into a payment outage. Brute force is still bounded
  by the lockout, which lives in PostgreSQL.
- **In-memory buckets per instance.** No Redis dependency, but the effective limit scales with the instance count
  and resets on every deployment.
- **Fixed window counter (`INCR` + `EXPIRE`).** Simpler, but allows twice the limit around a window boundary.
- **Limit at an API gateway** (Kong, NGINX, AWS API Gateway). Where it usually lives in production, in front of
  everything. Doing it in the application keeps the demo self-contained and lets limits use the authenticated user.

## Consequences

- Clients behind one NAT (a corporate network, mobile carrier-grade NAT) share the anonymous budget. Only sign-in,
  sign-up and refresh are anonymous, and 20 a minute is generous for people.
- Behind a reverse proxy the client address must come from `X-Forwarded-For`, set by a trusted proxy
  (`server.forward-headers-strategy`); otherwise every request appears to come from the proxy.

## Not done yet

Stripe's other limiters: a **concurrent requests** limiter (in-flight requests per user) and **load shedding** that
drops low-priority traffic first when the fleet is saturated. Velocity checks on money ("at most N transfers an
hour") are fraud rules, not rate limits, and belong in the transfer rules.

# ADR 0010: Authentication with short-lived ES256 access tokens and rotating refresh tokens

**Status:** Accepted (Phase 4)

## Context

Until Phase 3 every endpoint was open: anyone could open an account for anyone, move money out of any account, and
every event was attributed to `anonymous`. Three services need to know who is calling: the core, the audit service
and the notification service. Banks and e-wallets put authentication in a dedicated identity provider (Keycloak,
ForgeRock, Okta) speaking OAuth 2.0 / OpenID Connect, and financial APIs follow the FAPI 2.0 profile.

## Decision

### Who issues tokens

The core service contains a small token issuer (`com.payledger.security`): users, passwords, sign-in, access and
refresh tokens. The other services are **OAuth 2.0 resource servers** that only verify tokens.

| Choice | Why |
|---|---|
| Tokens signed with **ES256** (ECDSA P-256) | Asymmetric: only the core can sign, every service can verify. FAPI 2.0 allows PS256, ES256 and EdDSA, not RS256. Shorter tokens and faster signing than RSA. |
| Public keys published at **`/.well-known/jwks.json`** (RFC 7517) | Services fetch keys on first use and cache them; a token with an unknown `kid` triggers a refetch, so keys can be rotated by publishing the next key before using it. Downstream services hold no secret at all. |
| Access tokens in the **RFC 9068** JWT profile | `typ: at+jwt`, `iss`, `aud`, `sub`, `exp`, `iat`, `jti`, `client_id`, plus `roles` and `sid`. The `typ` check stops another kind of JWT (an ID token) from being replayed as an access token. Only `ES256` is accepted, which rules out `alg: none` and HMAC-with-the-public-key attacks. |
| **5-minute** access tokens | A JWT cannot be revoked; it is verified, never looked up. Keeping it short bounds what a stolen one is worth, and makes a role change effective within minutes (roles are read again at refresh). |
| **RFC 9728** protected resource metadata | Spring Security 7 serves `/.well-known/oauth-protected-resource` and points to it from every 401; it names the issuer whose tokens the API accepts. |

The user id (a UUID, never the username) is the token subject and the `owner_id` of the user's accounts.

### Refresh tokens: rotation with reuse detection (RFC 9700 §4.14.2)

Sign-in starts a **session** and returns an opaque refresh token (256 random bits, stored only as SHA-256). Each
refresh exchanges it for a new one; the old one stops working. If an exchanged token is presented again, either the
legitimate client or a thief is replaying a copy and the server cannot tell which, so it **revokes the whole
session**, as Auth0 and Okta do. Both must sign in again, and only the real user can.

The exchange is a single conditional `UPDATE ... WHERE used_at IS NULL`, so of two requests racing with one token,
exactly one wins (`parallelRefreshesWithOneTokenSucceedOnlyOnce`). Lifetimes follow the OWASP Session Management
Cheat Sheet for a high-value application: a **15-minute idle timeout** (each refresh token's lifetime) and an
**8-hour absolute** session lifetime. Sign-out revokes the session and always answers `204`, even for an unknown
token (RFC 7009), so the endpoint cannot be used to test tokens.

### Passwords

| Rule | Source |
|---|---|
| **Argon2id**, 19 MiB, 2 iterations, parallelism 1, 16-byte salt | OWASP Password Storage Cheat Sheet. Memory-hard, so GPU guessing is expensive; no 72-byte truncation as with bcrypt. Hashes carry an `{argon2}` prefix and are upgraded at the next sign-in if the cost is raised. |
| **15 to 64 characters**, counted in Unicode code points; no composition rules; no service name or username inside | NIST SP 800-63B-4 (2025) for a password that is the only factor |
| **Lockout after 5 consecutive failures**, for 15 minutes or until an operator unlocks | VCB Digibank and most Vietnamese banks lock after 5 wrong passwords. Not permanent, because anyone who knows a username can trigger it (and fraudsters do, then call the victim posing as the bank). |
| Unknown usernames get the same answer and the same work (a dummy hash check) as a wrong password | So neither the body nor the timing reveals which usernames exist (as Spring Security's `DaoAuthenticationProvider`) |

While a password is checked, the user row is locked (`FOR NO KEY UPDATE`). Without the lock, 30 parallel guesses all
read "0 failed attempts" and all 30 are checked: a lockout bypass. With it, exactly 5 are checked and the other 25 are
refused unseen (`parallelGuessesCannotGetPastTheLockout`; removing the lock makes the test fail).

The first ADMIN is created from `PAYLEDGER_ADMIN_PASSWORD` at startup if no ADMIN exists, like Keycloak's bootstrap
admin. No default password ships with the code.

### Signing key

Production reads the private key as a JWK from `PAYLEDGER_JWT_SIGNING_KEY` (a secret store). Without one, the core
generates a random key at startup and logs a warning: fine for one local instance, but tokens die with the process.

## Alternatives considered

- **Keycloak (or another IdP) in Docker Compose.** What a real bank would run, with MFA, brute-force detection and
  admin consoles for free. Rejected for this project because the point is to show the mechanics, and because it would
  hide them in configuration. The design keeps the door open: resource servers only know a JWKS URL and an issuer, so
  switching to Keycloak changes two properties in each service.
- **Spring Authorization Server.** A full OAuth 2.1 server (authorization code + PKCE). Right for third-party
  clients; heavy for one first-party app, and the browser flow belongs to Phase 6.
- **HS256 with a shared secret.** Every service that can verify could also mint tokens. Rejected.
- **Opaque access tokens with introspection (RFC 7662).** Revocable at once, but every request to every service
  becomes a call to the core. Short-lived JWTs trade instant revocation for no shared state.
- **Server-side sessions with cookies.** Stateful, needs CSRF protection, and does not suit machine clients or
  other services.

## Consequences

- An access token stays valid until it expires, even after sign-out or a lockout. The window is 5 minutes.
- Refresh tokens are returned in the JSON body, which suits mobile apps (stored in the Keychain / Keystore). A
  browser client (Phase 6) should keep them out of JavaScript, in an HttpOnly cookie behind a backend-for-frontend,
  as the OAuth 2.0 for Browser-Based Applications BCP recommends.
- A race between two tabs refreshing at the same moment ends the session (strict reuse detection). Auth0 and Okta
  offer a short grace period for this; it can be added if users hit it.

## Not done yet

- **Step-up authentication.** Since 1 July 2024, the State Bank of Vietnam's Decision 2345/QĐ-NHNN requires
  biometric verification for a transfer over 10 million VND, or once a day's transfers exceed 20 million VND; OTP
  covers the rest. That needs a second factor and an `acr` claim checked on the transfer endpoint.
- MFA, password reset, password change, device binding, breached-password check (k-anonymity API).
- Key rotation tooling (the JWKS already supports several keys).

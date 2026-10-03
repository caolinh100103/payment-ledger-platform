# ADR 0015: Browser sessions: refresh token in an HttpOnly cookie, access token in memory

**Status:** Accepted (Phase 6)

## Context

The web app signs people in with the tokens of [ADR 0010](0010-authentication-tokens.md): a 5-minute access token
and a rotating refresh token that keeps the session alive for up to 8 hours. The API endpoints return both in the
response body, which suits a server or a mobile app with a keychain. A single-page app has nowhere equally safe to
put them:

- `localStorage` / `sessionStorage` can be read by any script running in the page. One cross-site scripting (XSS) bug,
  or one compromised npm dependency, sends the refresh token to an attacker, who then keeps the session going from
  their own machine for up to 8 hours.
- Memory only means signing in again after every reload and in every new tab.

The IETF draft *OAuth 2.0 for Browser-Based Applications* compares the options. Its most secure pattern, a
**Backend for Frontend (BFF)**, keeps every token on a server and gives the browser only a session cookie. Its second,
the **token-mediating backend**, keeps the refresh token on the server side (in a cookie the browser cannot read) and
hands the app only short-lived access tokens.

## Decision

The core acts as the token-mediating backend for its own web app, with three endpoints next to the API ones:

| Endpoint | Does |
|---|---|
| `POST /api/v1/auth/browser/login` | As `/auth/login`, but the refresh token is set as a cookie, not returned |
| `POST /api/v1/auth/browser/refresh` | Reads the cookie, rotates it, returns a new access token |
| `POST /api/v1/auth/browser/logout` | Ends the session, deletes the cookie |

**The cookie.** `__Secure-payledger_refresh`, `HttpOnly` (no script can read it), `Secure` (HTTPS only; browsers also
accept `http://localhost`), `SameSite=Strict`, and `Path=/api/v1/auth/browser`: it is sent to those three endpoints
and never with an API call. Its `Max-Age` is the refresh token's idle timeout (15 minutes), renewed by each refresh.
Sessions, rotation and reuse detection are exactly those of the API (same tables, same code), so a stolen and replayed
cookie still ends the whole session.

**The access token** lives only in a JavaScript variable. Nothing refreshes in the background: a session nobody uses
expires after 15 minutes, as a bank's should. A reload or a new tab calls `/browser/refresh` first to learn whether
it is still signed in.

**CSRF.** A cookie is attached by the browser on its own, so a page on another site could make the browser call these
endpoints. Two defences, as the OWASP CSRF cheat sheet recommends for APIs:

1. `SameSite=Strict`: the browser does not send the cookie with any request started by another site.
2. Every browser endpoint requires an `X-Requested-With` header, or answers `403 CSRF_CHECK_FAILED`. A cross-origin
   request with a custom header needs a CORS preflight, and no endpoint grants one.

A forged refresh would gain nothing anyway: the new access token is in the response, which another origin cannot read.

**Same origin.** The browser talks to one origin only: Vite's proxy in development, nginx in production
(`frontend/nginx.conf`) routes `/api/v1/audit-events` and `/api/v1/notifications` to their services and the rest to
the core. No CORS is configured anywhere, which is what makes the custom-header check sufficient.

**Several tabs.** Refresh tokens rotate, and an exchanged one presented again ends the session. Two tabs refreshing at
the same moment with the same cookie would look exactly like theft. The app therefore serialises refreshes: within a
tab, concurrent callers share one request; across tabs, the refresh runs under a [Web Locks API](https://developer.mozilla.org/docs/Web/API/Web_Locks_API)
lock. The second tab's request is only built after the first one's response has stored the new cookie, so it
presents the new token. Signing out is broadcast to the other tabs (`BroadcastChannel`).

**Containing XSS.** nginx sends a strict Content Security Policy (`script-src 'self'`, no inline scripts, `connect-src
'self'`, `frame-ancestors 'none'`). If markup is injected anyway, it cannot load code from elsewhere or send data to
another host.

## Alternatives considered

- **Tokens in `localStorage`.** Common in tutorials, and what many SPAs do. A single XSS hands over a credential that
  outlives the page by hours. Not acceptable for a bank.
- **A separate BFF service.** The strongest option: not even the access token reaches the browser, so an XSS can act
  only while the victim's page is open. It is one more service in the request path, with its own session store
  (Redis) and a proxy for every API call. The token-mediating design gets most of the benefit (no long-lived
  credential reachable from JavaScript) at none of that cost, and the step to a BFF later is small, because the
  session logic already lives on the server. Revisit for production.
- **A grace period for reuse detection** instead of the cross-tab lock (Auth0's "reuse interval"): the server
  accepts the previous refresh token for a few seconds. It weakens detection for every client to fix a problem of
  one, and the lock removes the race instead of tolerating it.
- **Sign-in again on every reload** (memory only, no cookie). Some bank sites do this; it is safe but makes
  every new tab a new sign-in.

## Consequences

- The long-lived credential is out of reach of page scripts; an XSS is limited to what it can do with a 5-minute
  token while the page is open, and the CSP narrows that further.
- The core now reads one cookie. `SecurityConfig` still disables Spring Security's CSRF tokens, because no API call is
  authenticated by a cookie; the three cookie endpoints protect themselves and are covered by
  `BrowserSessionApiIntegrationTest`.
- The app must be served from the same site as the API. Hosting the frontend on a CDN domain of its own would need
  the cookie scope, CORS and the CSRF reasoning revisited.
- Plain HTTP on a host other than localhost does not work by default, since the cookie is `Secure`.
  `PAYLEDGER_SECURE_COOKIE=false` exists for trying the app that way; production is HTTPS.

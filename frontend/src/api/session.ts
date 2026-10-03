import { ApiError } from './problem'

/*
 * The web app's sign-in session (ADR 0015).
 *
 * The 5-minute access token lives only in this module's memory: not in localStorage or sessionStorage, where any
 * script injected into the page could read it and use it elsewhere. The refresh token never reaches JavaScript at
 * all: the core keeps it in an HttpOnly cookie that only /api/v1/auth/browser/* receives. A reload or a new tab
 * starts without an access token and gets one by refreshing.
 *
 * Refresh tokens rotate, and presenting one that was already exchanged ends the whole session (reuse detection,
 * RFC 9700). Two tabs refreshing at the same moment with the same cookie would look exactly like a stolen token. So
 * refreshes are serialised: within a tab by sharing one promise, across tabs with the Web Locks API. The second tab
 * only sends its request once the first one's response has stored the new cookie, so it presents the new token.
 *
 * Nothing refreshes in the background: a session that sees no use expires after the server's idle timeout
 * (15 minutes), as a bank's should.
 */

const LOGIN = '/api/v1/auth/browser/login'
const REFRESH = '/api/v1/auth/browser/refresh'
const LOGOUT = '/api/v1/auth/browser/logout'
const LOCK = 'payledger-token-refresh'
const CHANNEL = 'payledger-session'
/** Refresh this long before expiry, so that a token never expires on its way to the server. */
const EARLY_REFRESH_MS = 30_000

interface TokenResponse {
  accessToken: string
  expiresIn: number
  refreshExpiresIn: number
}

export interface SessionDependencies {
  fetch: (input: string, init: RequestInit) => Promise<Response>
  /** navigator.locks; where it is missing, refreshes are only serialised within the tab. */
  locks?: Pick<LockManager, 'request'>
  /** Tells the other tabs about a sign-out. */
  channel?: Pick<BroadcastChannel, 'postMessage' | 'addEventListener'>
  now: () => number
}

export class Session {
  private accessToken: string | null = null
  private expiresAt = 0
  private refreshing: Promise<string | null> | null = null
  private readonly listeners = new Set<() => void>()
  private readonly deps: SessionDependencies

  constructor(deps: SessionDependencies) {
    this.deps = deps
    deps.channel?.addEventListener('message', (event) => {
      if ((event as MessageEvent).data === 'signed-out') {
        this.end()
      }
    })
  }

  /** Throws an ApiError such as INVALID_CREDENTIALS or ACCOUNT_LOCKED. */
  async signIn(username: string, password: string): Promise<void> {
    const response = await this.post(LOGIN, { username, password })
    if (!response.ok) {
      throw await ApiError.fromResponse(response)
    }
    this.accept((await response.json()) as TokenResponse)
  }

  /** A token valid for at least 30 more seconds, refreshed if need be; null once the session has ended. */
  async getAccessToken(): Promise<string | null> {
    if (this.accessToken !== null && this.deps.now() < this.expiresAt - EARLY_REFRESH_MS) {
      return this.accessToken
    }
    return this.refresh()
  }

  /**
   * Exchanges the refresh cookie for a new access token; concurrent callers share one request. Resolves to null when
   * the session is over (expired, signed out, or ended by reuse detection). Throws when the outcome is unknown, e.g.
   * the network is down, because the session may well still be alive.
   */
  refresh(): Promise<string | null> {
    this.refreshing ??= this.acrossTabs(() => this.exchange()).finally(() => {
      this.refreshing = null
    })
    return this.refreshing
  }

  /** Ends the session on the server and in every tab of this browser. */
  async signOut(): Promise<void> {
    try {
      await this.post(LOGOUT)
    } catch {
      // The cookie dies with the idle timeout anyway; signing out here must not wait for the network.
    } finally {
      this.deps.channel?.postMessage('signed-out')
      this.end()
    }
  }

  isSignedIn(): boolean {
    return this.accessToken !== null
  }

  /** Called when the session ends, whatever ended it. */
  onEnd(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  private async exchange(): Promise<string | null> {
    const response = await this.post(REFRESH)
    if (response.ok) {
      this.accept((await response.json()) as TokenResponse)
      return this.accessToken
    }
    if (response.status === 401) {
      this.end()
      return null
    }
    throw await ApiError.fromResponse(response)
  }

  private acrossTabs<T>(task: () => Promise<T>): Promise<T> {
    return this.deps.locks ? (this.deps.locks.request(LOCK, task) as Promise<T>) : task()
  }

  private accept(tokens: TokenResponse) {
    this.accessToken = tokens.accessToken
    this.expiresAt = this.deps.now() + tokens.expiresIn * 1000
  }

  private end() {
    const wasSignedIn = this.accessToken !== null
    this.accessToken = null
    this.expiresAt = 0
    if (wasSignedIn) {
      this.listeners.forEach((listener) => listener())
    }
  }

  private async post(url: string, body?: unknown): Promise<Response> {
    try {
      return await this.deps.fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        // Proves a same-origin request: a cross-site page cannot add it without a CORS preflight (OWASP).
        headers: { 'X-Requested-With': 'XMLHttpRequest', ...(body ? { 'Content-Type': 'application/json' } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      })
    } catch (cause) {
      throw ApiError.network(cause)
    }
  }
}

export const session = new Session({
  fetch: (input, init) => fetch(input, init),
  locks: typeof navigator !== 'undefined' && 'locks' in navigator ? navigator.locks : undefined,
  channel: typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(CHANNEL) : undefined,
  now: () => Date.now(),
})

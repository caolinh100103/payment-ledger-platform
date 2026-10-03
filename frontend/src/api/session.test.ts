import { describe, expect, it, vi } from 'vitest'
import { Session, type SessionDependencies } from './session'

function tokens(accessToken: string, expiresIn = 300) {
  return Response.json({ accessToken, tokenType: 'Bearer', expiresIn, refreshExpiresIn: 900 })
}

function setUp(overrides: Partial<SessionDependencies> = {}) {
  let now = 1_000_000
  const fetch = vi.fn<SessionDependencies['fetch']>()
  const session = new Session({ now: () => now, ...overrides, fetch })
  return { session, deps: { fetch }, advance: (ms: number) => (now += ms) }
}

describe('Session', () => {
  it('refreshes once for any number of simultaneous callers', async () => {
    const { session, deps } = setUp()
    let respond: (response: Response) => void = () => {}
    deps.fetch.mockReturnValue(new Promise((resolve) => (respond = resolve)))

    const callers = Promise.all([session.getAccessToken(), session.getAccessToken(), session.getAccessToken()])
    respond(tokens('a1'))

    expect(await callers).toEqual(['a1', 'a1', 'a1'])
    expect(deps.fetch).toHaveBeenCalledTimes(1)
    expect(deps.fetch).toHaveBeenCalledWith('/api/v1/auth/browser/refresh', expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: expect.objectContaining({ 'X-Requested-With': 'XMLHttpRequest' }),
    }))
  })

  it('keeps the token until 30 seconds before it expires, then refreshes', async () => {
    const { session, deps, advance } = setUp()
    deps.fetch.mockResolvedValueOnce(tokens('a1')).mockResolvedValueOnce(tokens('a2'))

    expect(await session.getAccessToken()).toBe('a1')
    advance(269_000)
    expect(await session.getAccessToken()).toBe('a1')
    advance(2_000)
    expect(await session.getAccessToken()).toBe('a2')
    expect(deps.fetch).toHaveBeenCalledTimes(2)
  })

  it('takes the cross-tab lock around each refresh, so two tabs never present the same refresh token', async () => {
    const order: string[] = []
    const locks = {
      request: vi.fn(async (name: string, task: () => Promise<unknown>) => {
        order.push(`lock ${name}`)
        const result = await task()
        order.push('unlock')
        return result
      }),
    }
    const { session, deps } = setUp({ locks: locks as unknown as SessionDependencies['locks'] })
    deps.fetch.mockImplementation(async () => {
      order.push('refresh')
      return tokens('a1')
    })

    await session.refresh()

    expect(order).toEqual(['lock payledger-token-refresh', 'refresh', 'unlock'])
  })

  it('ends the session when the refresh token is refused', async () => {
    const { session, deps } = setUp()
    const ended = vi.fn()
    session.onEnd(ended)
    deps.fetch.mockResolvedValueOnce(tokens('a1'))
    await session.getAccessToken()

    deps.fetch.mockResolvedValueOnce(Response.json({ status: 401, title: 'Unauthorized', code: 'REFRESH_TOKEN_REUSED' },
      { status: 401 }))

    expect(await session.refresh()).toBeNull()
    expect(session.isSignedIn()).toBe(false)
    expect(ended).toHaveBeenCalledOnce()
  })

  it('does not end the session when the server cannot be reached', async () => {
    const { session, deps } = setUp()
    deps.fetch.mockResolvedValueOnce(tokens('a1'))
    await session.getAccessToken()
    deps.fetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await expect(session.refresh()).rejects.toMatchObject({ code: 'NETWORK_ERROR', retryable: true })
    expect(session.isSignedIn()).toBe(true)
  })

  it('reports why sign-in failed', async () => {
    const { session, deps } = setUp()
    deps.fetch.mockResolvedValueOnce(Response.json(
      { status: 401, title: 'Unauthorized', detail: 'Too many failed sign-ins', code: 'ACCOUNT_LOCKED' },
      { status: 401, headers: { 'X-Trace-Id': '4bf92f3577b34da6a3ce929d0e0e4736' } }))

    await expect(session.signIn('alice', 'wrong')).rejects.toMatchObject({
      status: 401,
      code: 'ACCOUNT_LOCKED',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    })
  })

  it('signs out every tab, even when the server cannot be reached', async () => {
    const channel = { postMessage: vi.fn(), addEventListener: vi.fn() }
    const { session, deps } = setUp({ channel })
    deps.fetch.mockResolvedValueOnce(tokens('a1'))
    await session.getAccessToken()
    deps.fetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await session.signOut()

    expect(channel.postMessage).toHaveBeenCalledWith('signed-out')
    expect(session.isSignedIn()).toBe(false)
  })

  it('ends when another tab signs out', async () => {
    let onMessage: (event: MessageEvent) => void = () => {}
    const channel = {
      postMessage: vi.fn(),
      addEventListener: vi.fn((_type: string, listener: (event: MessageEvent) => void) => (onMessage = listener)),
    }
    const { session, deps } = setUp({ channel: channel as unknown as SessionDependencies['channel'] })
    deps.fetch.mockResolvedValueOnce(tokens('a1'))
    await session.getAccessToken()

    onMessage(new MessageEvent('message', { data: 'signed-out' }))

    expect(session.isSignedIn()).toBe(false)
  })
})

import { describe, expect, it, vi } from 'vitest'
import { createApi, unwrap } from './client'

function auth(tokens: string[]) {
  return {
    getAccessToken: vi.fn(async () => tokens[0] ?? null),
    refresh: vi.fn(async () => tokens[1] ?? null),
  }
}

describe('createApi', () => {
  it('sends the access token as a bearer token', async () => {
    const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => Response.json([]))
    const api = createApi(auth(['t1']), 'http://localhost', fetch)

    await unwrap(api.core.GET('/api/v1/accounts'))

    expect(fetch.mock.calls[0]?.[0].headers.get('Authorization')).toBe('Bearer t1')
  })

  it('refreshes once and repeats the request, body included, when the token is refused', async () => {
    const fetch = vi.fn<(request: Request) => Promise<Response>>()
      .mockResolvedValueOnce(Response.json({ status: 401, title: 'Unauthorized', code: 'INVALID_TOKEN' },
        { status: 401 }))
      .mockResolvedValueOnce(Response.json({ id: 'a1' }, { status: 201 }))
    const tokens = auth(['stale', 'fresh'])
    const api = createApi(tokens, 'http://localhost', fetch)

    await unwrap(api.core.POST('/api/v1/accounts', { body: { currency: 'VND' } }))

    const retry = fetch.mock.calls[1]?.[0]
    expect(tokens.refresh).toHaveBeenCalledOnce()
    expect(retry?.headers.get('Authorization')).toBe('Bearer fresh')
    expect(await retry?.json()).toEqual({ currency: 'VND' })
  })

  it('turns a problem into an ApiError with its code and the trace id to quote', async () => {
    const fetch = vi.fn(async () => Response.json(
      { type: 'about:blank', title: 'Unprocessable Content', status: 422, detail: 'Insufficient funds',
        code: 'INSUFFICIENT_FUNDS', transferId: '9b0e2f4c-0000-4000-8000-000000000000' },
      { status: 422, headers: { 'Content-Type': 'application/problem+json', 'X-Trace-Id': 'abc123' } }))
    const api = createApi(auth(['t1']), 'http://localhost', fetch)

    const error = await unwrap(api.core.GET('/api/v1/accounts')).catch((e: unknown) => e)

    expect(error).toMatchObject({
      status: 422,
      code: 'INSUFFICIENT_FUNDS',
      traceId: 'abc123',
      retryable: false,
      problem: { transferId: '9b0e2f4c-0000-4000-8000-000000000000' },
    })
  })

  it('reports a session that has ended without calling the server', async () => {
    const fetch = vi.fn()
    const api = createApi(auth([]), 'http://localhost', fetch)

    await expect(unwrap(api.core.GET('/api/v1/accounts'))).rejects.toMatchObject({ code: 'SESSION_ENDED' })
    expect(fetch).not.toHaveBeenCalled()
  })
})

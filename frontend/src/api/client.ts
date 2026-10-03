import createClient from 'openapi-fetch'
import type { paths as AuditPaths } from './generated/audit'
import type { paths as CorePaths } from './generated/core'
import type { paths as NotificationPaths } from './generated/notification'
import { ApiError } from './problem'
import type { Session } from './session'

/**
 * Typed clients for the three services, generated from their OpenAPI documents (ADR 0016): a field renamed or an enum
 * value added in the backend fails `npm run build` here, instead of breaking the app in front of a customer.
 *
 * All three are reached through this origin (Vite's proxy in development, nginx in production), so the refresh cookie
 * stays same-site and no CORS is involved.
 */
export function createApi(
  auth: Pick<Session, 'getAccessToken' | 'refresh'>,
  baseUrl: string,
  fetchImpl: (request: Request) => Promise<Response> = (request) => fetch(request),
) {
  const options = { baseUrl, fetch: authorized(auth, fetchImpl) }
  return {
    core: createClient<CorePaths>(options),
    audit: createClient<AuditPaths>(options),
    notification: createClient<NotificationPaths>(options),
    /** For the few endpoints anyone may call, such as sign-up: no token, so no refresh either. */
    anonymous: createClient<CorePaths>({ baseUrl, fetch: anonymous(fetchImpl) }),
  }
}

export type Api = ReturnType<typeof createApi>

/** Adds the access token to every request; if the token is refused anyway, refreshes it once and tries again. */
function authorized(
  auth: Pick<Session, 'getAccessToken' | 'refresh'>,
  fetchImpl: (request: Request) => Promise<Response>,
) {
  async function send(request: Request, token: string): Promise<Response> {
    request.headers.set('Authorization', `Bearer ${token}`)
    try {
      return await fetchImpl(request)
    } catch (cause) {
      throw ApiError.network(cause)
    }
  }

  return async (request: Request): Promise<Response> => {
    const retry = request.clone()
    const token = await auth.getAccessToken()
    if (token === null) {
      throw new ApiError(401, 'SESSION_ENDED', 'Your session has ended. Please sign in again.')
    }
    const response = await send(request, token)
    if (response.status !== 401) {
      return response
    }
    // E.g. the signing key was rotated. A 401 is answered before anything is done, so sending again is safe.
    const fresh = await auth.refresh()
    return fresh === null ? response : send(retry, fresh)
  }
}

function anonymous(fetchImpl: (request: Request) => Promise<Response>) {
  return async (request: Request): Promise<Response> => {
    try {
      return await fetchImpl(request)
    } catch (cause) {
      throw ApiError.network(cause)
    }
  }
}

/** The body of a successful response; any other response becomes an ApiError with its problem and trace id. */
export async function unwrap<R extends { data?: unknown; error?: unknown; response: Response }>(
  call: Promise<R>,
): Promise<NonNullable<R['data']>> {
  const { data, error, response } = await call
  if (!response.ok) {
    throw await ApiError.fromResponse(response, error)
  }
  return data as NonNullable<R['data']>
}

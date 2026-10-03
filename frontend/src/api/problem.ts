import type { components } from './generated/core'

/** An RFC 9457 problem as the three services send it, with a stable `code` (see the README's error tables). */
export type Problem = components['schemas']['Problem']

/** Codes after which the same request may simply be sent again (with the same Idempotency-Key, if it has one). */
const RETRYABLE = new Set(['NETWORK_ERROR', 'LOCK_TIMEOUT', 'IDEMPOTENCY_KEY_IN_PROGRESS', 'RATE_LIMITED'])

/**
 * A failed API call. Carries the problem and the X-Trace-Id of the response: shown to the user as a reference to
 * quote to support, it finds the request in the logs, the traces and the audit trail (ADR 0014).
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly problem: Problem | undefined
  readonly traceId: string | undefined

  constructor(status: number, code: string, message: string, problem?: Problem, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.problem = problem
    this.traceId = traceId
  }

  /** The request was not processed, or its outcome is unknown: sending it again is safe and may succeed. */
  get retryable(): boolean {
    return RETRYABLE.has(this.code) || this.status === 502 || this.status === 503 || this.status === 504
  }

  static async fromResponse(response: Response, body?: unknown): Promise<ApiError> {
    const traceId = response.headers.get('X-Trace-Id') ?? undefined
    let problem = isProblem(body) ? body : undefined
    if (problem === undefined && body === undefined) {
      try {
        const parsed: unknown = await response.clone().json()
        problem = isProblem(parsed) ? parsed : undefined
      } catch {
        // Not JSON: a proxy's error page, for instance.
      }
    }
    const code = problem?.code ?? `HTTP_${response.status}`
    const message = problem?.detail ?? problem?.title ?? `The server answered ${response.status}`
    return new ApiError(response.status, code, message, problem, traceId)
  }

  static network(cause: unknown): ApiError {
    const error = new ApiError(0, 'NETWORK_ERROR', 'PayLedger could not be reached. Check your connection.')
    error.cause = cause
    return error
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

function isProblem(value: unknown): value is Problem {
  return typeof value === 'object' && value !== null && 'status' in value && 'title' in value
}

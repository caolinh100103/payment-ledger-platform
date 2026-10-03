import { useState, type ReactNode } from 'react'
import { isApiError } from '../api/problem'
import { accountTail } from '../lib/format'
import { formatMoney } from '../lib/money'

export function Money({ amount, currency, signed = false }: { amount: number; currency: string; signed?: boolean }) {
  const tone = !signed || amount === 0 ? '' : amount > 0 ? ' money--in' : ' money--out'
  return <span className={`money${tone}`}>{formatMoney(amount, currency, { signed })}</span>
}

type Tone = 'neutral' | 'good' | 'bad' | 'warn' | 'info'

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`badge badge--${tone}`}>{children}</span>
}

const statusTones: Record<string, Tone> = {
  ACTIVE: 'good',
  COMPLETED: 'good',
  FROZEN: 'warn',
  REVERSED: 'warn',
  PENDING: 'info',
  CLOSED: 'neutral',
  FAILED: 'bad',
}

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={statusTones[status] ?? 'neutral'}>{status.charAt(0) + status.slice(1).toLowerCase()}</Badge>
}

/** What a customer should read for each problem code; the server's `detail` follows in smaller print. */
const headlines: Record<string, string> = {
  INSUFFICIENT_FUNDS: 'There is not enough money in the account.',
  SOURCE_ACCOUNT_NOT_ACTIVE: 'The account is frozen or closed. Contact support.',
  DESTINATION_ACCOUNT_NOT_ACTIVE: "The recipient's account cannot receive money right now.",
  CURRENCY_MISMATCH: 'The two accounts are in different currencies.',
  ACCOUNT_TYPE_NOT_ALLOWED: 'Money cannot be sent to this account.',
  SAME_ACCOUNT: 'Choose two different accounts.',
  RESOURCE_NOT_FOUND: 'Nothing was found with that reference.',
  TRANSFER_NOT_REVERSIBLE: 'This movement cannot be reversed.',
  INVALID_CREDENTIALS: 'The username or password is incorrect.',
  ACCOUNT_LOCKED: 'Too many failed sign-ins. Try again later, or call support to unlock your profile.',
  USERNAME_TAKEN: 'That username is already taken.',
  PASSWORD_POLICY_VIOLATION: 'Choose a different password.',
  RATE_LIMITED: 'Too many requests. Wait a moment, then try again.',
  LOCK_TIMEOUT: 'The account is busy with another payment. Try again; you will not be charged twice.',
  IDEMPOTENCY_KEY_IN_PROGRESS: 'This payment is still being processed. Try again in a moment.',
  NETWORK_ERROR: 'PayLedger could not be reached. Check your connection.',
  SESSION_ENDED: 'Your session has ended. Please sign in again.',
  ACCESS_DENIED: 'You are not allowed to do this.',
  INVALID_REQUEST: 'Some of the details are not valid.',
  UNSUPPORTED_CURRENCY: 'That currency is not supported.',
  INVALID_ACCOUNT_STATUS_TRANSITION: 'The account cannot change to that status now.',
  CONCURRENT_MODIFICATION: 'Someone else changed this at the same time. Reload and try again.',
}

/**
 * An error as a bank shows it: what happened in plain words, and a reference to quote to support. The reference is
 * the request's trace id, which finds it in the logs, the traces and the audit trail.
 */
export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!isApiError(error)) {
    return (
      <div className="notice notice--error" role="alert">
        <strong>Something went wrong.</strong>
        <p>{error instanceof Error ? error.message : String(error)}</p>
      </div>
    )
  }
  const headline = headlines[error.code] ?? error.problem?.title ?? 'Something went wrong.'
  const detail = error.problem?.detail
  return (
    <div className="notice notice--error" role="alert">
      <strong>{headline}</strong>
      {detail && detail !== headline && <p>{detail}</p>}
      {error.problem?.invalidParams && error.problem.invalidParams.length > 0 && (
        <ul>
          {error.problem.invalidParams.map((param) => (
            <li key={`${param.name}:${param.reason}`}>
              <code>{param.name}</code> {param.reason}
            </li>
          ))}
        </ul>
      )}
      <div className="notice__footer">
        {error.traceId && (
          <span className="reference">
            Reference <code>{error.traceId}</code> <CopyButton value={error.traceId} label="Copy reference" />
          </span>
        )}
        {error.retryable && onRetry && (
          <button type="button" className="button button--small" onClick={onRetry}>
            Try again
          </button>
        )}
      </div>
    </div>
  )
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <span className="spinner" aria-hidden="true" />
      {label}
    </div>
  )
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {children && <div>{children}</div>}
    </div>
  )
}

export function CopyButton({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false)
  if (typeof navigator === 'undefined' || !navigator.clipboard) {
    return null
  }
  return (
    <button
      type="button"
      className="copy"
      aria-label={label}
      title={label}
      onClick={() =>
        void navigator.clipboard.writeText(value).then(() => {
          setCopied(true)
          setTimeout(() => setCopied(false), 1500)
        })
      }
    >
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

/** An account number as on a bank's SMS ("...8b10"), with the full id on hover and a copy button. */
export function AccountId({ id, copy = true }: { id: string; copy?: boolean }) {
  return (
    <span className="account-id">
      <code title={id}>{accountTail(id)}</code>
      {copy && <CopyButton value={id} label="Copy account number" />}
    </span>
  )
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="page-header__subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-header__actions">{actions}</div>}
    </header>
  )
}

export function Field({
  label,
  htmlFor,
  hint,
  error,
  children,
}: {
  label: string
  htmlFor: string
  hint?: ReactNode
  error?: string
  children: ReactNode
}) {
  return (
    <div className={`field${error ? ' field--invalid' : ''}`}>
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {error ? (
        <span className="field__error" id={`${htmlFor}-error`}>
          {error}
        </span>
      ) : (
        hint && <span className="field__hint">{hint}</span>
      )}
    </div>
  )
}

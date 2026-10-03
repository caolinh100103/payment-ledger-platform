import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/problem'
import { useRefreshAfterMovement, useTransfer, type Transfer } from '../api/queries'
import { useApi, useUser } from '../auth/AuthContext'
import { hasRole } from '../auth/roles'
import { AccountId, CopyButton, ErrorNotice, Field, Loading, Money, PageHeader, StatusBadge } from '../components/ui'
import { formatDateTime, newIdempotencyKey } from '../lib/format'
import { formatMoney } from '../lib/money'

const typeLabels: Record<Transfer['type'], string> = {
  DEPOSIT: 'Top-up',
  TRANSFER: 'Transfer',
  REVERSAL: 'Reversal',
}

/** A deposit, transfer or reversal, rejected ones included. Either party, or an operator. */
export function TransferDetailsPage() {
  const { id = '' } = useParams()
  const transfer = useTransfer(id)
  const user = useUser()

  if (transfer.isPending) {
    return <Loading />
  }
  if (transfer.isError) {
    return <ErrorNotice error={transfer.error} onRetry={() => void transfer.refetch()} />
  }
  const t = transfer.data
  const linkAccounts = hasRole(user, 'OPERATOR')
  return (
    <>
      <PageHeader
        title={`${typeLabels[t.type]} of ${t.currency}`}
        subtitle={<>Reference <code>{t.id}</code> <CopyButton value={t.id} label="Copy reference" /></>}
      />
      <section className="card">
        <div className="transfer-head">
          <span className="transfer-head__amount"><Money amount={t.amount} currency={t.currency} /></span>
          <StatusBadge status={t.status} />
        </div>
        <dl className="summary">
          <dt>From</dt>
          <dd><AccountRef id={t.sourceAccountId} link={linkAccounts} /></dd>
          <dt>To</dt>
          <dd><AccountRef id={t.destinationAccountId} link={linkAccounts} /></dd>
          {t.description && (<><dt>{t.type === 'REVERSAL' ? 'Reason' : 'Message'}</dt><dd>{t.description}</dd></>)}
          <dt>Created</dt>
          <dd>{formatDateTime(t.createdAt, { seconds: true })}</dd>
          {t.updatedAt !== t.createdAt && (<><dt>Last change</dt><dd>{formatDateTime(t.updatedAt, { seconds: true })}</dd></>)}
          {t.failureCode && (
            <>
              <dt>Why it failed</dt>
              <dd><code>{t.failureCode}</code> {t.failureReason}</dd>
            </>
          )}
          {t.reversalOf && (
            <>
              <dt>Reverses</dt>
              <dd><Link to={`/transfers/${t.reversalOf}`}>{t.reversalOf}</Link></dd>
            </>
          )}
        </dl>
        {t.status === 'REVERSED' && (
          <div className="notice notice--warn">
            This movement was reversed: the same amount went back with a separate, compensating movement. The original
            entries are never changed.
          </div>
        )}
      </section>
      {hasRole(user, 'OPERATOR') && t.status === 'COMPLETED' && t.type !== 'REVERSAL' && <ReverseForm transfer={t} />}
    </>
  )
}

function AccountRef({ id, link }: { id: string; link: boolean }) {
  return link ? <Link to={`/accounts/${id}`}><code>{id}</code></Link> : <AccountId id={id} />
}

/**
 * The compensating movement an operator makes for a disputed or mistaken payment. Like any money movement it
 * carries an Idempotency-Key, created with the form and kept across retries.
 */
function ReverseForm({ transfer }: { transfer: Transfer }) {
  const api = useApi()
  const navigate = useNavigate()
  const refresh = useRefreshAfterMovement()
  const [reason, setReason] = useState('')
  const [key] = useState(newIdempotencyKey)
  const reverse = useMutation({
    mutationFn: async () => {
      const result = await api.core.POST('/api/v1/transfers/{id}/reversals', {
        params: { path: { id: transfer.id }, header: { 'Idempotency-Key': key } },
        body: { reason: reason.trim() },
      })
      if (!result.response.ok || result.data === undefined) {
        throw await ApiError.fromResponse(result.response, result.error)
      }
      return result.data
    },
    onSuccess: async (reversal) => {
      await refresh()
      await navigate(`/transfers/${reversal.id}`)
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (reason.trim() && confirm(`Move ${formatMoney(transfer.amount, transfer.currency)} back to the sender?`)) {
      reverse.mutate()
    }
  }

  return (
    <form className="card form" onSubmit={submit}>
      <h2>Reverse this {transfer.type === 'DEPOSIT' ? 'top-up' : 'transfer'}</h2>
      <p className="muted">
        Moves the full amount back with a new movement. Refused if the recipient no longer has the money; the attempt is
        recorded and can be repeated later.
      </p>
      {reverse.isError && <ErrorNotice error={reverse.error} onRetry={() => reverse.mutate()} />}
      <Field label="Reason" htmlFor="reason" hint="Kept as the reversal's description, for the audit trail.">
        <input id="reason" required maxLength={140} value={reason} onChange={(event) => setReason(event.target.value)} />
      </Field>
      <button type="submit" className="button button--warn" disabled={!reason.trim() || reverse.isPending}>
        {reverse.isPending ? 'Reversing…' : 'Reverse'}
      </button>
    </form>
  )
}

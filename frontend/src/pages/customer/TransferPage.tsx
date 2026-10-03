import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/problem'
import { useAccounts, useRefreshAfterMovement, type Account, type Transfer } from '../../api/queries'
import { useApi } from '../../auth/AuthContext'
import { AccountId, CopyButton, Empty, ErrorNotice, Field, Loading, Money, PageHeader } from '../../components/ui'
import { accountTail, newIdempotencyKey, UUID_PATTERN } from '../../lib/format'
import { formatMoney, parseAmount } from '../../lib/money'

interface Draft {
  source: Account
  destinationAccountId: string
  amount: number
  description: string
}

/**
 * Where the payment is. The Idempotency-Key is created when the customer reaches the confirmation step and is kept
 * for every attempt to send that payment: a double click, a retry after a timeout or a dropped connection all
 * present the same key, and the server answers with the outcome of the first attempt instead of paying twice
 * (ADR 0005). Changing the payment starts over with a new key.
 */
type Step =
  | { name: 'edit' }
  | { name: 'review'; draft: Draft; key: string }
  | { name: 'done'; draft: Draft; key: string; transfer: Transfer; replayed: boolean }
  | { name: 'rejected'; draft: Draft; key: string; error: ApiError }

export function TransferPage() {
  const accounts = useAccounts()
  const [step, setStep] = useState<Step>({ name: 'edit' })

  if (accounts.isPending) {
    return <Loading />
  }
  if (accounts.isError) {
    return <ErrorNotice error={accounts.error} onRetry={() => void accounts.refetch()} />
  }
  const usable = accounts.data.filter((account) => account.status === 'ACTIVE')
  return (
    <>
      <PageHeader title="Send money" subtitle="To any PayLedger account, in the same currency." />
      {usable.length === 0 ? (
        <Empty title="You have no active account to send from.">
          <Link to="/accounts">Open an account</Link> first.
        </Empty>
      ) : step.name === 'edit' ? (
        <TransferForm accounts={usable} onReview={(draft) => setStep({ name: 'review', draft, key: newIdempotencyKey() })} />
      ) : step.name === 'review' ? (
        <Review draft={step.draft} idempotencyKey={step.key}
                onEdit={() => setStep({ name: 'edit' })}
                onDone={(transfer, replayed) => setStep({ ...step, name: 'done', transfer, replayed })}
                onRejected={(error) => setStep({ ...step, name: 'rejected', error })} />
      ) : step.name === 'done' ? (
        <Done step={step} onAnother={() => setStep({ name: 'edit' })} />
      ) : (
        <Rejected step={step} onAnother={() => setStep({ name: 'edit' })} />
      )}
    </>
  )
}

function TransferForm({ accounts, onReview }: { accounts: Account[]; onReview: (draft: Draft) => void }) {
  const [searchParams] = useSearchParams()
  const preferred = accounts.find((account) => account.id === searchParams.get('from')) ?? accounts[0]
  const [sourceId, setSourceId] = useState(preferred?.id ?? '')
  const [destination, setDestination] = useState('')
  const [amountText, setAmountText] = useState('')
  const [description, setDescription] = useState('')
  const [touched, setTouched] = useState(false)

  const source = accounts.find((account) => account.id === sourceId)
  const currency = source?.currency ?? 'VND'
  const amount = parseAmount(amountText, currency)
  const destinationId = destination.trim().toLowerCase()

  const destinationError = !UUID_PATTERN.test(destinationId)
    ? 'Enter the full account number, e.g. 3f2a9c1e-…'
    : destinationId === sourceId ? 'Choose an account other than the one you send from' : undefined
  const amountError = !amount.ok ? amount.error
    : source && amount.minor > source.balance ? `More than the ${formatMoney(source.balance, currency)} available`
      : undefined
  const ownOthers = accounts.filter((account) => account.id !== sourceId && account.currency === currency)

  function submit(event: FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (source && amount.ok && !destinationError && !amountError) {
      onReview({ source, destinationAccountId: destinationId, amount: amount.minor, description: description.trim() })
    }
  }

  return (
    <form className="card form" onSubmit={submit} noValidate>
      <Field label="From" htmlFor="source">
        <select id="source" value={sourceId} onChange={(event) => setSourceId(event.target.value)}>
          {accounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.currency} {accountTail(account.id)} · {formatMoney(account.balance, account.currency)}
            </option>
          ))}
        </select>
      </Field>
      <Field label="To account number" htmlFor="destination" error={touched ? destinationError : undefined}
             hint="The recipient's PayLedger account number.">
        <input id="destination" value={destination} spellCheck={false} autoComplete="off"
               placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
               onChange={(event) => setDestination(event.target.value)} />
      </Field>
      {ownOthers.length > 0 && (
        <div className="chips" aria-label="Your other accounts">
          <span className="muted small">Or one of yours:</span>
          {ownOthers.map((account) => (
            <button key={account.id} type="button" className="chip" onClick={() => setDestination(account.id)}>
              {account.currency} {accountTail(account.id)}
            </button>
          ))}
        </div>
      )}
      <Field label="Amount" htmlFor="amount" error={touched ? amountError : undefined}>
        <div className="input-suffix">
          <input id="amount" inputMode="decimal" autoComplete="off" value={amountText} placeholder="0"
                 onChange={(event) => setAmountText(event.target.value)} />
          <span>{currency}</span>
        </div>
      </Field>
      <Field label="Message (optional)" htmlFor="description" hint={`${description.length}/140 · shown to the recipient`}>
        <input id="description" maxLength={140} value={description}
               onChange={(event) => setDescription(event.target.value)} />
      </Field>
      <button type="submit" className="button button--primary">Continue</button>
    </form>
  )
}

function Review({ draft, idempotencyKey, onEdit, onDone, onRejected }: {
  draft: Draft
  idempotencyKey: string
  onEdit: () => void
  onDone: (transfer: Transfer, replayed: boolean) => void
  onRejected: (error: ApiError) => void
}) {
  const api = useApi()
  const refresh = useRefreshAfterMovement()
  const send = useMutation({
    mutationFn: async () => {
      const result = await api.core.POST('/api/v1/transfers', {
        params: { header: { 'Idempotency-Key': idempotencyKey } },
        body: {
          sourceAccountId: draft.source.id,
          destinationAccountId: draft.destinationAccountId,
          amount: draft.amount,
          currency: draft.source.currency,
          description: draft.description || undefined,
        },
      })
      if (!result.response.ok || result.data === undefined) {
        throw await ApiError.fromResponse(result.response, result.error)
      }
      return { transfer: result.data, replayed: result.response.headers.get('Idempotent-Replayed') === 'true' }
    },
    onSuccess: ({ transfer, replayed }) => {
      void refresh()
      onDone(transfer, replayed)
    },
    onError: (error) => {
      // A business rule said no: the attempt is recorded as FAILED, and retrying would only replay that answer.
      if (error instanceof ApiError && error.status === 422 && error.problem?.transferId) {
        void refresh()
        onRejected(error)
      }
    },
  })

  return (
    <section className="card review">
      <h2>Check and confirm</h2>
      <dl className="summary">
        <dt>From</dt>
        <dd>{draft.source.currency} account <AccountId id={draft.source.id} copy={false} /></dd>
        <dt>To</dt>
        <dd><code>{draft.destinationAccountId}</code></dd>
        <dt>Amount</dt>
        <dd className="summary__amount"><Money amount={draft.amount} currency={draft.source.currency} /></dd>
        {draft.description && (<><dt>Message</dt><dd>{draft.description}</dd></>)}
      </dl>
      {send.isError && <ErrorNotice error={send.error} onRetry={() => send.mutate()} />}
      <div className="button-row">
        <button type="button" className="button button--primary" disabled={send.isPending}
                onClick={() => send.mutate()}>
          {send.isPending ? 'Sending…' : 'Confirm and send'}
        </button>
        <button type="button" className="button button--ghost" disabled={send.isPending} onClick={onEdit}>
          Change
        </button>
      </div>
    </section>
  )
}

function Done({ step, onAnother }: { step: Extract<Step, { name: 'done' }>; onAnother: () => void }) {
  const { transfer, replayed } = step
  return (
    <section className="card result result--success" aria-live="polite">
      <div className="result__icon" aria-hidden="true">✓</div>
      <h2>Money sent</h2>
      <p className="result__amount"><Money amount={transfer.amount} currency={transfer.currency} /></p>
      <p>to account <AccountId id={transfer.destinationAccountId} /></p>
      {replayed && (
        <div className="notice notice--info">
          This payment had already gone through, so it was not made again. This is its original confirmation.
        </div>
      )}
      <p className="muted">
        Reference <code>{transfer.id}</code> <CopyButton value={transfer.id} label="Copy reference" />
      </p>
      <details className="tech">
        <summary>Technical details</summary>
        <dl className="summary">
          <dt>Idempotency-Key</dt>
          <dd><code>{step.key}</code></dd>
          <dt>Status</dt>
          <dd>{transfer.status}</dd>
          <dt>Replayed</dt>
          <dd>{replayed ? 'yes: the original response, byte for byte' : 'no'}</dd>
        </dl>
      </details>
      <div className="button-row">
        <Link to={`/transfers/${transfer.id}`} className="button">View details</Link>
        <button type="button" className="button button--ghost" onClick={onAnother}>Send more money</button>
      </div>
    </section>
  )
}

function Rejected({ step, onAnother }: { step: Extract<Step, { name: 'rejected' }>; onAnother: () => void }) {
  const transferId = step.error.problem?.transferId
  return (
    <section className="card result result--failure" aria-live="polite">
      <div className="result__icon" aria-hidden="true">!</div>
      <h2>The money was not sent</h2>
      <ErrorNotice error={step.error} />
      <p className="muted">Nothing left your account. The attempt is recorded, with its reason.</p>
      <div className="button-row">
        {transferId && <Link to={`/transfers/${transferId}`} className="button">View the attempt</Link>}
        <button type="button" className="button button--primary" onClick={onAnother}>Change the payment</button>
      </div>
    </section>
  )
}

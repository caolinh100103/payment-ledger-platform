import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { unwrap } from '../../api/client'
import { keys, useAccounts, type Account } from '../../api/queries'
import { useApi } from '../../auth/AuthContext'
import { AccountId, Empty, ErrorNotice, Loading, Money, PageHeader, StatusBadge } from '../../components/ui'
import { currencies, type Currency } from '../../lib/money'

export function AccountsPage() {
  const accounts = useAccounts()
  return (
    <>
      <PageHeader
        title="Your accounts"
        subtitle="Balances are updated the moment a payment completes."
        actions={<Link to="/transfer" className="button button--primary">Send money</Link>}
      />
      {accounts.isPending && <Loading />}
      {accounts.isError && <ErrorNotice error={accounts.error} onRetry={() => void accounts.refetch()} />}
      {accounts.data && (
        <>
          <Totals accounts={accounts.data} />
          {accounts.data.length === 0 ? (
            <Empty title="You have no account yet.">Open one below to receive and send money.</Empty>
          ) : (
            <div className="cards">
              {accounts.data.map((account) => <AccountCard key={account.id} account={account} />)}
            </div>
          )}
          {accounts.data.some((account) => account.balance === 0 && account.status === 'ACTIVE') && (
            <div className="notice notice--info">
              <strong>Adding money</strong>
              <p>
                Top up from your bank to the account number shown on the card. Your bank tells PayLedger when the
                money arrives, and it appears here straight away.
              </p>
            </div>
          )}
          <OpenAccount />
        </>
      )}
    </>
  )
}

function Totals({ accounts }: { accounts: Account[] }) {
  const totals = new Map<string, number>()
  for (const account of accounts) {
    if (account.status !== 'CLOSED') {
      totals.set(account.currency, (totals.get(account.currency) ?? 0) + account.balance)
    }
  }
  if (totals.size === 0) {
    return null
  }
  return (
    <section className="totals" aria-label="Total balance">
      {[...totals].map(([currency, total]) => (
        <div key={currency} className="totals__item">
          <span className="totals__label">Total in {currency}</span>
          <span className="totals__amount"><Money amount={total} currency={currency} /></span>
        </div>
      ))}
    </section>
  )
}

function AccountCard({ account }: { account: Account }) {
  return (
    <Link to={`/accounts/${account.id}`} className={`card card--link account-card account-card--${account.status.toLowerCase()}`}>
      <div className="account-card__top">
        <span className="account-card__currency">{account.currency} account</span>
        <StatusBadge status={account.status} />
      </div>
      <div className="account-card__balance"><Money amount={account.balance} currency={account.currency} /></div>
      <div className="account-card__number">
        Account <AccountId id={account.id} />
      </div>
    </Link>
  )
}

function OpenAccount() {
  const api = useApi()
  const queryClient = useQueryClient()
  const [currency, setCurrency] = useState<Currency>('VND')
  const open = useMutation({
    mutationFn: () => unwrap(api.core.POST('/api/v1/accounts', { body: { currency } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.accounts() }),
  })
  return (
    <section className="card">
      <h2>Open an account</h2>
      <p className="muted">One account per currency you want to hold. It is free and ready at once.</p>
      {open.isError && <ErrorNotice error={open.error} />}
      <div className="inline-form">
        <label htmlFor="currency" className="visually-hidden">Currency</label>
        <select id="currency" value={currency} onChange={(event) => setCurrency(event.target.value as Currency)}>
          {currencies.map((code) => <option key={code} value={code}>{code}</option>)}
        </select>
        <button type="button" className="button" disabled={open.isPending} onClick={() => open.mutate()}>
          {open.isPending ? 'Opening…' : 'Open account'}
        </button>
      </div>
    </section>
  )
}

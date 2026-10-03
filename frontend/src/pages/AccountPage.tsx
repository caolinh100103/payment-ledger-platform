import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { unwrap } from '../api/client'
import { keys, useAccount, useStatement, type Account, type StatementEntry } from '../api/queries'
import { useApi, useUser } from '../auth/AuthContext'
import { hasRole } from '../auth/roles'
import { Badge, Empty, ErrorNotice, Loading, Money, PageHeader, StatusBadge } from '../components/ui'
import { accountTail, formatDateTime } from '../lib/format'

/** One account and its statement. The customer who owns it, or an operator helping them. */
export function AccountPage() {
  const { id = '' } = useParams()
  const account = useAccount(id)
  const user = useUser()

  if (account.isPending) {
    return <Loading />
  }
  if (account.isError) {
    return <ErrorNotice error={account.error} onRetry={() => void account.refetch()} />
  }
  const data = account.data
  return (
    <>
      <PageHeader
        title={`${data.currency} account`}
        subtitle={<>Account number <code>{data.id}</code></>}
        actions={hasRole(user, 'CUSTOMER') && data.status === 'ACTIVE' && (
          <Link to={`/transfer?from=${data.id}`} className="button button--primary">Send money</Link>
        )}
      />
      <section className="card account-summary">
        <div>
          <span className="muted">Available balance</span>
          <div className="account-summary__balance"><Money amount={data.balance} currency={data.currency} /></div>
        </div>
        <div className="account-summary__meta">
          <StatusBadge status={data.status} />
          <span className="muted">Opened {formatDateTime(data.createdAt)}</span>
        </div>
        <AccountActions account={data} />
      </section>
      <Statement account={data} />
    </>
  )
}

function AccountActions({ account }: { account: Account }) {
  const api = useApi()
  const user = useUser()
  const queryClient = useQueryClient()
  const action = useMutation({
    mutationFn: (change: 'freeze' | 'unfreeze' | 'close') => {
      const params = { params: { path: { id: account.id } } }
      switch (change) {
        case 'freeze':
          return unwrap(api.core.POST('/api/v1/accounts/{id}/freeze', params))
        case 'unfreeze':
          return unwrap(api.core.POST('/api/v1/accounts/{id}/unfreeze', params))
        case 'close':
          return unwrap(api.core.POST('/api/v1/accounts/{id}/close', params))
      }
    },
    onSuccess: (updated) => {
      queryClient.setQueryData(keys.account(account.id), updated)
      return queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
  const operator = hasRole(user, 'OPERATOR')
  const canClose = account.status !== 'CLOSED' && account.balance === 0

  if (!operator && !canClose) {
    return null
  }
  return (
    <div className="account-summary__actions">
      {action.isError && <ErrorNotice error={action.error} />}
      <div className="button-row">
        {operator && account.status === 'ACTIVE' && (
          <button type="button" className="button button--warn" disabled={action.isPending}
                  onClick={() => confirm('Freeze this account? No money can leave it until it is unfrozen.')
                    && action.mutate('freeze')}>
            Freeze
          </button>
        )}
        {operator && account.status === 'FROZEN' && (
          <button type="button" className="button" disabled={action.isPending} onClick={() => action.mutate('unfreeze')}>
            Unfreeze
          </button>
        )}
        {canClose && (
          <button type="button" className="button button--ghost" disabled={action.isPending}
                  onClick={() => confirm('Close this account for good?') && action.mutate('close')}>
            Close account
          </button>
        )}
      </div>
    </div>
  )
}

function Statement({ account }: { account: Account }) {
  const statement = useStatement(account.id)
  const entries = statement.data?.pages.flatMap((page) => page.data) ?? []
  return (
    <section className="card">
      <h2>Statement</h2>
      {statement.isPending && <Loading />}
      {statement.isError && <ErrorNotice error={statement.error} onRetry={() => void statement.refetch()} />}
      {statement.isSuccess && entries.length === 0 && (
        <Empty title="No movements yet.">Payments in and out of this account will be listed here.</Empty>
      )}
      {entries.length > 0 && (
        <table className="table statement">
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Details</th>
              <th scope="col" className="numeric">Amount</th>
              <th scope="col" className="numeric">Balance</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => <StatementRow key={entry.id} entry={entry} />)}
          </tbody>
        </table>
      )}
      {statement.hasNextPage && (
        <button type="button" className="button button--ghost button--block" disabled={statement.isFetchingNextPage}
                onClick={() => void statement.fetchNextPage()}>
          {statement.isFetchingNextPage ? 'Loading…' : 'Show earlier movements'}
        </button>
      )}
    </section>
  )
}

function describe(entry: StatementEntry): string {
  const other = accountTail(entry.counterpartyAccountId)
  switch (entry.type) {
    case 'DEPOSIT':
      return 'Top-up from your bank'
    case 'TRANSFER':
      return entry.direction === 'DEBIT' ? `To account ${other}` : `From account ${other}`
    case 'REVERSAL':
      return entry.direction === 'CREDIT' ? `Refund from account ${other}` : `Reversal to account ${other}`
  }
}

function StatementRow({ entry }: { entry: StatementEntry }) {
  const signed = entry.direction === 'CREDIT' ? entry.amount : -entry.amount
  return (
    <tr>
      <td className="nowrap">{formatDateTime(entry.createdAt)}</td>
      <td>
        <Link to={`/transfers/${entry.transferId}`}>{describe(entry)}</Link>
        {entry.transferStatus === 'REVERSED' && <> <Badge tone="warn">Reversed</Badge></>}
        {entry.description && <div className="muted small">{entry.description}</div>}
      </td>
      <td className="numeric"><Money amount={signed} currency={entry.currency} signed /></td>
      <td className="numeric muted"><Money amount={entry.balanceAfter} currency={entry.currency} /></td>
    </tr>
  )
}


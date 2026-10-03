import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { unwrap } from '../../api/client'
import { keys, useAccounts, useNotifications } from '../../api/queries'
import { useApi } from '../../auth/AuthContext'
import { roleLabels, type User } from '../../auth/roles'
import { Badge, CopyButton, Empty, ErrorNotice, Loading, Money, PageHeader, StatusBadge } from '../../components/ui'
import { accountTail, formatDateTime } from '../../lib/format'
import { MessageList } from '../customer/MessagesPage'

/**
 * The support desk: a customer calls, the operator finds them by username, sees their profile, accounts and the SMS
 * they were sent, and can lift a sign-in lockout or freeze an account. Every one of these actions is audited.
 */
export function CustomersPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const username = searchParams.get('username') ?? ''
  const [input, setInput] = useState(username)

  function search(event: FormEvent) {
    event.preventDefault()
    setSearchParams(input.trim() ? { username: input.trim() } : {})
  }

  return (
    <>
      <PageHeader title="Customers" subtitle="Find a customer by their exact username." />
      <form className="inline-form card" onSubmit={search} role="search">
        <label htmlFor="username" className="visually-hidden">Username</label>
        <input id="username" placeholder="Username" value={input} autoComplete="off"
               onChange={(event) => setInput(event.target.value)} />
        <button type="submit" className="button button--primary">Find</button>
      </form>
      {username && <Lookup username={username} />}
    </>
  )
}

function Lookup({ username }: { username: string }) {
  const api = useApi()
  const users = useQuery({
    queryKey: keys.userByName(username),
    queryFn: () => unwrap(api.core.GET('/api/v1/users', { params: { query: { username } } })),
  })
  if (users.isPending) {
    return <Loading />
  }
  if (users.isError) {
    return <ErrorNotice error={users.error} onRetry={() => void users.refetch()} />
  }
  const user = users.data[0]
  if (!user) {
    return <Empty title={`No user is called "${username}".`}>Usernames must match exactly; case does not matter.</Empty>
  }
  return (
    <>
      <Profile user={user} />
      {user.role === 'CUSTOMER' && <CustomerAccounts ownerId={user.id} />}
      {user.role === 'CUSTOMER' && <CustomerMessages recipientId={user.id} />}
    </>
  )
}

function CustomerMessages({ recipientId }: { recipientId: string }) {
  const messages = useNotifications(recipientId)
  return (
    <section className="card">
      <h2>Latest messages sent</h2>
      <MessageList query={messages} limit={5} />
    </section>
  )
}

function Profile({ user }: { user: User }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const unlock = useMutation({
    mutationFn: () => unwrap(api.core.POST('/api/v1/users/{id}/unlock', { params: { path: { id: user.id } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.userByName(user.username) }),
  })
  return (
    <section className="card">
      <div className="profile">
        <div>
          <h2>{user.username}</h2>
          <p className="muted">
            {roleLabels[user.role]} · user <code>{user.id}</code> <CopyButton value={user.id} label="Copy user id" />
          </p>
        </div>
        {user.lockedUntil ? <Badge tone="bad">Locked out</Badge> : <Badge tone="good">Can sign in</Badge>}
      </div>
      <dl className="summary">
        <dt>Customer since</dt>
        <dd>{formatDateTime(user.createdAt)}</dd>
        <dt>Last sign-in</dt>
        <dd>{user.lastLoginAt ? formatDateTime(user.lastLoginAt) : 'Never'}</dd>
        {user.lockedUntil && (<><dt>Locked until</dt><dd>{formatDateTime(user.lockedUntil)}</dd></>)}
      </dl>
      {unlock.isError && <ErrorNotice error={unlock.error} />}
      {user.lockedUntil && (
        <button type="button" className="button" disabled={unlock.isPending} onClick={() => unlock.mutate()}>
          {unlock.isPending ? 'Unlocking…' : 'Unlock now'}
        </button>
      )}
      <p className="small muted">
        Their actions in the audit trail: actor <code>user:{user.id}</code>
      </p>
    </section>
  )
}

function CustomerAccounts({ ownerId }: { ownerId: string }) {
  const accounts = useAccounts(ownerId)
  return (
    <section className="card">
      <h2>Accounts</h2>
      {accounts.isPending && <Loading />}
      {accounts.isError && <ErrorNotice error={accounts.error} />}
      {accounts.data?.length === 0 && <Empty title="No accounts." />}
      {accounts.data && accounts.data.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Account</th>
              <th scope="col">Status</th>
              <th scope="col" className="numeric">Balance</th>
              <th scope="col">Opened</th>
            </tr>
          </thead>
          <tbody>
            {accounts.data.map((account) => (
              <tr key={account.id}>
                <td><Link to={`/accounts/${account.id}`}>{account.currency} {accountTail(account.id)}</Link></td>
                <td><StatusBadge status={account.status} /></td>
                <td className="numeric"><Money amount={account.balance} currency={account.currency} /></td>
                <td>{formatDateTime(account.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

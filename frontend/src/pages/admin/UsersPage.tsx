import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { unwrap } from '../../api/client'
import { useApi } from '../../auth/AuthContext'
import { roleLabels, type Role, type User } from '../../auth/roles'
import { ErrorNotice, Field, PageHeader } from '../../components/ui'

const STAFF_ROLES: Role[] = ['OPERATOR', 'AUDITOR', 'ADMIN']

/** Staff accounts. Customers open their own profile; an ADMIN creates everyone else. */
export function UsersPage() {
  const api = useApi()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>('OPERATOR')
  const [created, setCreated] = useState<User[]>([])
  const create = useMutation({
    mutationFn: () => unwrap(api.core.POST('/api/v1/users', { body: { username, password, role } })),
    onSuccess: (user) => {
      setCreated((previous) => [user, ...previous])
      setUsername('')
      setPassword('')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate()
  }

  return (
    <>
      <PageHeader title="Users" subtitle="Create staff accounts. To find or unlock a customer, use Customers." />
      <form className="card form" onSubmit={submit}>
        <h2>New staff member</h2>
        {create.isError && <ErrorNotice error={create.error} />}
        <Field label="Username" htmlFor="username">
          <input id="username" required autoComplete="off" value={username}
                 onChange={(event) => setUsername(event.target.value)} />
        </Field>
        <Field label="Initial password" htmlFor="password" hint="15 to 64 characters; hand it over in person.">
          <input id="password" type="password" required autoComplete="new-password" value={password}
                 onChange={(event) => setPassword(event.target.value)} />
        </Field>
        <Field label="Role" htmlFor="role"
               hint="Separation of duties: no staff role can move a customer's money, ADMIN included.">
          <select id="role" value={role} onChange={(event) => setRole(event.target.value as Role)}>
            {STAFF_ROLES.map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
          </select>
        </Field>
        <button type="submit" className="button button--primary" disabled={create.isPending}>
          {create.isPending ? 'Creating…' : 'Create user'}
        </button>
      </form>
      {created.length > 0 && (
        <section className="card">
          <h2>Created just now</h2>
          <ul className="plain-list">
            {created.map((user) => (
              <li key={user.id}>
                <strong>{user.username}</strong> · {roleLabels[user.role]} · <code>{user.id}</code>{' '}
                <Link to={`/audit?resource=${user.id}`}>audit trail</Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { unwrap } from '../../api/client'
import { keys, type ApiKey } from '../../api/queries'
import { useApi } from '../../auth/AuthContext'
import { Badge, CopyButton, Empty, ErrorNotice, Field, Loading, PageHeader } from '../../components/ui'
import { formatDateTime } from '../../lib/format'

type Scope = ApiKey['scopes'][number]
const SCOPES: { scope: Scope; description: string }[] = [
  { scope: 'deposits:write', description: 'Credit customers with money received from their bank (top-ups)' },
]

/** Keys for machine clients, such as the partner bank that reports top-ups. */
export function ApiKeysPage() {
  const api = useApi()
  const apiKeys = useQuery({
    queryKey: keys.apiKeys,
    queryFn: () => unwrap(api.core.GET('/api/v1/api-keys')),
  })
  return (
    <>
      <PageHeader title="API keys" subtitle="For partner systems. A key is shown once, when it is issued." />
      <IssueKey />
      <section className="card">
        <h2>Issued keys</h2>
        {apiKeys.isPending && <Loading />}
        {apiKeys.isError && <ErrorNotice error={apiKeys.error} onRetry={() => void apiKeys.refetch()} />}
        {apiKeys.data?.length === 0 && <Empty title="No keys yet." />}
        {apiKeys.data && apiKeys.data.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Key</th>
                <th scope="col">Scopes</th>
                <th scope="col">Last used</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {apiKeys.data.map((key) => <KeyRow key={key.id} apiKey={key} />)}
            </tbody>
          </table>
        )}
      </section>
    </>
  )
}

function keyStatus(key: ApiKey): { label: string; tone: 'good' | 'bad' | 'neutral' } {
  if (key.revokedAt) {
    return { label: 'Revoked', tone: 'bad' }
  }
  if (key.expiresAt && new Date(key.expiresAt) < new Date()) {
    return { label: 'Expired', tone: 'neutral' }
  }
  return { label: 'Active', tone: 'good' }
}

function KeyRow({ apiKey }: { apiKey: ApiKey }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const revoke = useMutation({
    mutationFn: () => unwrap(api.core.POST('/api/v1/api-keys/{id}/revoke', { params: { path: { id: apiKey.id } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.apiKeys }),
  })
  const status = keyStatus(apiKey)
  return (
    <tr>
      <td>
        {apiKey.name}
        <div className="muted small">by {apiKey.createdBy}, {formatDateTime(apiKey.createdAt)}</div>
      </td>
      <td><code>{apiKey.prefix}…</code></td>
      <td>{apiKey.scopes.map((scope) => <code key={scope}>{scope}</code>)}</td>
      <td>{apiKey.lastUsedAt ? formatDateTime(apiKey.lastUsedAt) : 'Never'}</td>
      <td>
        <Badge tone={status.tone}>{status.label}</Badge>
        {apiKey.expiresAt && !apiKey.revokedAt && (
          <div className="muted small">until {formatDateTime(apiKey.expiresAt)}</div>
        )}
      </td>
      <td className="numeric">
        {status.label === 'Active' && (
          <button type="button" className="button button--small button--danger" disabled={revoke.isPending}
                  onClick={() => confirm(`Revoke "${apiKey.name}"? Requests with it fail at once.`) && revoke.mutate()}>
            Revoke
          </button>
        )}
        {revoke.isError && <ErrorNotice error={revoke.error} />}
      </td>
    </tr>
  )
}

function IssueKey() {
  const api = useApi()
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [scopes, setScopes] = useState<Scope[]>(['deposits:write'])
  const [expires, setExpires] = useState('')
  const issue = useMutation({
    mutationFn: () => unwrap(api.core.POST('/api/v1/api-keys', {
      body: { name, scopes, expiresAt: expires ? new Date(`${expires}T23:59:59`).toISOString() : undefined },
    })),
    onSuccess: () => {
      setName('')
      return queryClient.invalidateQueries({ queryKey: keys.apiKeys })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    issue.mutate()
  }

  return (
    <form className="card form" onSubmit={submit}>
      <h2>Issue a key</h2>
      {issue.isError && <ErrorNotice error={issue.error} />}
      {issue.data && (
        <div className="notice notice--warn secret" role="status">
          <strong>Copy the key now. It is not stored and will not be shown again.</strong>
          <p><code>{issue.data.key}</code> <CopyButton value={issue.data.key} label="Copy key" /></p>
          <p className="small">Only its SHA-256 is kept. If it is lost, revoke it and issue a new one.</p>
        </div>
      )}
      <Field label="Name" htmlFor="key-name" hint="Who uses it, e.g. Vietcombank top-up webhook">
        <input id="key-name" required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} />
      </Field>
      <fieldset className="field">
        <legend>Scopes</legend>
        {SCOPES.map(({ scope, description }) => (
          <label key={scope} className="checkbox">
            <input type="checkbox" checked={scopes.includes(scope)}
                   onChange={(event) => setScopes(event.target.checked
                     ? [...scopes, scope] : scopes.filter((s) => s !== scope))} />
            <code>{scope}</code> {description}
          </label>
        ))}
      </fieldset>
      <Field label="Expires (optional)" htmlFor="expires" hint="PCI DSS asks for system credentials to be rotated.">
        <input id="expires" type="date" value={expires} min={new Date().toISOString().slice(0, 10)}
               onChange={(event) => setExpires(event.target.value)} />
      </Field>
      <button type="submit" className="button button--primary" disabled={issue.isPending || scopes.length === 0}>
        {issue.isPending ? 'Issuing…' : 'Issue key'}
      </button>
    </form>
  )
}

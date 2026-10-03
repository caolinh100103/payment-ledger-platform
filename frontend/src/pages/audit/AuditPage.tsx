import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { unwrap } from '../../api/client'
import { useAuditLatest, useAuditTrail, type AuditEvent, type ChainVerification } from '../../api/queries'
import { useApi } from '../../auth/AuthContext'
import { Badge, Empty, ErrorNotice, Loading, PageHeader } from '../../components/ui'
import { formatDateTime } from '../../lib/format'

/**
 * The auditor's view of the tamper-evident trail (ADR 0009): what happened lately and who did it, the full story of
 * one payment or account, and a check that no record was altered or removed since it was written.
 */
export function AuditPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const resourceId = searchParams.get('resource') ?? ''
  const actor = searchParams.get('actor') ?? ''

  function show(params: { resource?: string; actor?: string }) {
    const next: Record<string, string> = {}
    if (params.resource) next.resource = params.resource
    if (params.actor) next.actor = params.actor
    setSearchParams(next)
  }

  return (
    <>
      <PageHeader title="Audit trail" subtitle="Every event of the platform, append-only and hash-chained." />
      <Verification />
      <Filters key={`${resourceId}|${actor}`} resourceId={resourceId} actor={actor} onShow={show} />
      {resourceId ? (
        <Trail resourceId={resourceId} onShow={show} />
      ) : (
        <Latest actor={actor} onShow={show} />
      )}
    </>
  )
}

function Verification() {
  const api = useApi()
  const verify = useMutation({
    mutationFn: () => unwrap(api.audit.GET('/api/v1/audit-events/verification')),
  })
  return (
    <section className="card verification">
      <div>
        <h2>Integrity</h2>
        <p className="muted">
          Re-hashes the whole chain: an edited, re-hashed or deleted record is found, even one changed by a database
          superuser.
        </p>
      </div>
      <button type="button" className="button" disabled={verify.isPending} onClick={() => verify.mutate()}>
        {verify.isPending ? 'Verifying…' : 'Verify the chain'}
      </button>
      {verify.isError && <ErrorNotice error={verify.error} />}
      {verify.data && <VerificationResult result={verify.data} />}
    </section>
  )
}

function VerificationResult({ result }: { result: ChainVerification }) {
  if (result.valid) {
    return (
      <div className="notice notice--success" role="status">
        <strong>The chain is intact.</strong>
        <p>
          {result.checkedEvents} records checked
          {result.headSeq !== null && <>; head #{result.headSeq}, hash <code>{result.headHash?.slice(0, 16)}…</code></>}.
        </p>
      </div>
    )
  }
  return (
    <div className="notice notice--error" role="alert">
      <strong>The chain is broken at record #{result.firstInvalidSeq}.</strong>
      <p>{result.problem}</p>
      <p>{result.checkedEvents} records before it are intact.</p>
    </div>
  )
}

function Filters({ resourceId, actor, onShow }: {
  resourceId: string
  actor: string
  onShow: (params: { resource?: string; actor?: string }) => void
}) {
  const [resource, setResource] = useState(resourceId)
  const [who, setWho] = useState(actor)

  function submit(event: FormEvent) {
    event.preventDefault()
    onShow(resource.trim() ? { resource: resource.trim() } : { actor: who.trim() })
  }

  return (
    <form className="card filters" onSubmit={submit} role="search">
      <div className="field">
        <label htmlFor="resource">Resource</label>
        <input id="resource" placeholder="A transfer, account, user or API key id" value={resource}
               onChange={(event) => setResource(event.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="actor">Actor</label>
        <input id="actor" placeholder="e.g. user:3f2a… or apikey:…" value={who} disabled={resource.trim() !== ''}
               onChange={(event) => setWho(event.target.value)} />
      </div>
      <div className="button-row">
        <button type="submit" className="button button--primary">Show</button>
        {(resourceId || actor) && (
          <button type="button" className="button button--ghost" onClick={() => {
            setResource('')
            setWho('')
            onShow({})
          }}>
            Latest events
          </button>
        )}
      </div>
    </form>
  )
}

function Latest({ actor, onShow }: { actor: string; onShow: (params: { resource?: string; actor?: string }) => void }) {
  const latest = useAuditLatest(actor)
  const events = latest.data?.pages.flatMap((page) => page.data) ?? []
  return (
    <section className="card">
      <h2>{actor ? <>Done by <code>{actor}</code></> : 'Latest events'}</h2>
      {latest.isPending && <Loading />}
      {latest.isError && <ErrorNotice error={latest.error} onRetry={() => void latest.refetch()} />}
      {latest.isSuccess && events.length === 0 && <Empty title="No events." />}
      {events.length > 0 && <EventTable events={events} onShow={onShow} />}
      {latest.hasNextPage && (
        <button type="button" className="button button--ghost button--block" disabled={latest.isFetchingNextPage}
                onClick={() => void latest.fetchNextPage()}>
          {latest.isFetchingNextPage ? 'Loading…' : 'Show older events'}
        </button>
      )}
    </section>
  )
}

function Trail({ resourceId, onShow }: {
  resourceId: string
  onShow: (params: { resource?: string; actor?: string }) => void
}) {
  const trail = useAuditTrail(resourceId)
  return (
    <section className="card">
      <h2>The story of <code>{resourceId}</code>, oldest first</h2>
      {trail.isPending && <Loading />}
      {trail.isError && <ErrorNotice error={trail.error} onRetry={() => void trail.refetch()} />}
      {trail.data?.length === 0 && <Empty title="No events for this resource." />}
      {trail.data && trail.data.length > 0 && <EventTable events={trail.data} onShow={onShow} />}
    </section>
  )
}

function EventTable({ events, onShow }: {
  events: AuditEvent[]
  onShow: (params: { resource?: string; actor?: string }) => void
}) {
  const [open, setOpen] = useState<number | null>(null)
  return (
    <table className="table audit">
      <thead>
        <tr>
          <th scope="col">#</th>
          <th scope="col">When</th>
          <th scope="col">Event</th>
          <th scope="col">Actor</th>
          <th scope="col">Resource</th>
        </tr>
      </thead>
      <tbody>
        {events.map((event) => (
          <EventRow key={event.seq} event={event} open={open === event.seq} onShow={onShow}
                    onToggle={() => setOpen(open === event.seq ? null : event.seq)} />
        ))}
      </tbody>
    </table>
  )
}

function EventRow({ event, open, onToggle, onShow }: {
  event: AuditEvent
  open: boolean
  onToggle: () => void
  onShow: (params: { resource?: string; actor?: string }) => void
}) {
  const action = event.action.replace(/^com\.payledger\./, '')
  return (
    <>
      <tr>
        <td className="muted">{event.seq}</td>
        <td className="nowrap">{formatDateTime(event.occurredAt, { seconds: true })}</td>
        <td>
          <button type="button" className="link" aria-expanded={open} onClick={onToggle}>{action}</button>
          {/failed|reuse|locked/i.test(action) && <> <Badge tone="bad">attention</Badge></>}
        </td>
        <td>
          <button type="button" className="link mono" onClick={() => onShow({ actor: event.actor })}>{event.actor}</button>
        </td>
        <td>
          {event.resourceId && (
            <button type="button" className="link mono" onClick={() => onShow({ resource: event.resourceId ?? '' })}>
              {event.resourceId.slice(0, 8)}…
            </button>
          )}
        </td>
      </tr>
      {open && (
        <tr className="audit__details">
          <td colSpan={5}>
            <dl className="summary summary--compact">
              <dt>Recorded</dt>
              <dd>{formatDateTime(event.recordedAt, { seconds: true })}</dd>
              <dt>Hash</dt>
              <dd><code>{event.hash}</code></dd>
              <dt>Previous hash</dt>
              <dd><code>{event.prevHash}</code></dd>
            </dl>
            <pre className="json">{JSON.stringify(event.event, null, 2)}</pre>
          </td>
        </tr>
      )}
    </>
  )
}

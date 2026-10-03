import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { Field, PageHeader } from '../../components/ui'
import { UUID_PATTERN } from '../../lib/format'

/** A customer quotes a payment's reference; the operator opens it, and can reverse it from there. */
export function TransferLookupPage() {
  const navigate = useNavigate()
  const [reference, setReference] = useState('')
  const [touched, setTouched] = useState(false)
  const id = reference.trim().toLowerCase()
  const error = UUID_PATTERN.test(id) ? undefined : 'A reference looks like 9b0e2f4c-…, 36 characters'

  function submit(event: FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (!error) {
      void navigate(`/transfers/${id}`)
    }
  }

  return (
    <>
      <PageHeader title="Transfers" subtitle="Open a deposit, transfer or reversal by its reference." />
      <form className="card form" onSubmit={submit} role="search" noValidate>
        <Field label="Reference" htmlFor="reference" error={touched ? error : undefined}
               hint="Shown to the customer after every payment, and in the audit trail.">
          <input id="reference" value={reference} spellCheck={false} autoComplete="off"
                 onChange={(event) => setReference(event.target.value)} />
        </Field>
        <button type="submit" className="button button--primary">Open</button>
      </form>
    </>
  )
}

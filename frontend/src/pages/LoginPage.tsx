import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { ErrorNotice, Field } from '../components/ui'

export interface LocationState {
  from?: string
  reason?: 'ended'
}

export function LoginPage() {
  const { signIn } = useAuth()
  const location = useLocation()
  const state = (location.state ?? {}) as LocationState
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setError(null)
    try {
      // Once signed in, <SignedOut> takes the user back to where the session ended, or to their home page.
      await signIn(username, password)
    } catch (e) {
      setError(e)
      setPassword('')
    } finally {
      setPending(false)
    }
  }

  return (
    <AuthCard title="Sign in" subtitle="Online banking and back office">
      {state.reason === 'ended' && (
        <div className="notice notice--info" role="status">
          Your session has ended. For your security, please sign in again.
        </div>
      )}
      {error !== null && <ErrorNotice error={error} />}
      <form onSubmit={(event) => void submit(event)} className="form">
        <Field label="Username" htmlFor="username">
          <input id="username" autoComplete="username" required value={username}
                 onChange={(event) => setUsername(event.target.value)} />
        </Field>
        <Field label="Password" htmlFor="password">
          <input id="password" type="password" autoComplete="current-password" required value={password}
                 onChange={(event) => setPassword(event.target.value)} />
        </Field>
        <button type="submit" className="button button--primary button--block" disabled={pending}>
          {pending ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      {import.meta.env.VITE_DEMO_CREDENTIALS && (
        <p className="demo-hint">Demo: {import.meta.env.VITE_DEMO_CREDENTIALS}</p>
      )}
      <p className="auth-card__footer">
        New to PayLedger? <Link to="/signup">Open a profile</Link>
      </p>
    </AuthCard>
  )
}

export function AuthCard({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="brand brand--large">
          <span className="brand__mark" aria-hidden="true">P</span>
          PayLedger
        </div>
        <h1>{title}</h1>
        {subtitle && <p className="auth-card__subtitle">{subtitle}</p>}
        {children}
      </div>
    </div>
  )
}

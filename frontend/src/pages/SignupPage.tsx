import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { unwrap } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { ErrorNotice, Field } from '../components/ui'
import { AuthCard } from './LoginPage'

// The backend's rules (NIST SP 800-63B-4): 15 to 64 characters, no composition rules. Checked here only to answer
// sooner; the server decides.
const MIN_PASSWORD = 15
const MAX_PASSWORD = 64
const USERNAME = /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/

export function SignupPage() {
  const { api, signIn } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)

  const usernameError = username && !USERNAME.test(username)
    ? "3 to 64 letters, digits, '.', '_' or '-', starting with a letter or digit" : undefined
  const passwordError = password && (password.length < MIN_PASSWORD || password.length > MAX_PASSWORD)
    ? `${MIN_PASSWORD} to ${MAX_PASSWORD} characters` : undefined
  const confirmationError = confirmation && confirmation !== password ? 'The passwords do not match' : undefined
  const valid = username && password && confirmation && !usernameError && !passwordError && !confirmationError

  async function submit(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setError(null)
    try {
      await unwrap(api.anonymous.POST('/api/v1/auth/signup', { body: { username, password } }))
      await signIn(username, password)
    } catch (e) {
      setError(e)
    } finally {
      setPending(false)
    }
  }

  return (
    <AuthCard title="Open a profile" subtitle="It takes a minute. You can open accounts once you are in.">
      {error !== null && <ErrorNotice error={error} />}
      <form onSubmit={(event) => void submit(event)} className="form" noValidate>
        <Field label="Username" htmlFor="username" error={usernameError}>
          <input id="username" autoComplete="username" required value={username}
                 onChange={(event) => setUsername(event.target.value)} />
        </Field>
        <Field label="Password" htmlFor="password" error={passwordError}
               hint="At least 15 characters. A few unrelated words make a strong, memorable passphrase.">
          <input id="password" type="password" autoComplete="new-password" required value={password}
                 onChange={(event) => setPassword(event.target.value)} />
        </Field>
        <Field label="Repeat the password" htmlFor="confirmation" error={confirmationError}>
          <input id="confirmation" type="password" autoComplete="new-password" required value={confirmation}
                 onChange={(event) => setConfirmation(event.target.value)} />
        </Field>
        <button type="submit" className="button button--primary button--block" disabled={!valid || pending}>
          {pending ? 'Opening…' : 'Open my profile'}
        </button>
      </form>
      <p className="auth-card__footer">
        Already a customer? <Link to="/login">Sign in</Link>
      </p>
    </AuthCard>
  )
}

import { useQueryClient } from '@tanstack/react-query'
import { createContext, use, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { unwrap, type Api } from '../api/client'
import type { Session } from '../api/session'
import type { User } from './roles'

export type AuthState =
  | { status: 'loading' }
  | { status: 'unreachable'; error: unknown }
  | { status: 'signedOut'; reason?: 'ended' }
  | { status: 'signedIn'; user: User }

interface AuthContextValue {
  state: AuthState
  api: Api
  signIn(username: string, password: string): Promise<void>
  signOut(): Promise<void>
  retry(): void
}

const AuthContext = createContext<AuthContextValue | null>(null)

/**
 * Who is signed in. On start, a refresh tells whether the browser still holds a live session (its HttpOnly cookie),
 * so a reload or a second tab does not ask for the password again within the idle timeout.
 */
export function AuthProvider({ session, api, children }: { session: Session; api: Api; children: ReactNode }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<AuthState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  const loadUser = useCallback(async () => {
    const user = await unwrap(api.core.GET('/api/v1/users/me'))
    setState({ status: 'signedIn', user })
  }, [api])

  useEffect(() => {
    let cancelled = false
    async function resume() {
      try {
        const token = await session.refresh()
        if (cancelled) {
          return
        }
        if (token === null) {
          setState({ status: 'signedOut' })
        } else {
          await loadUser()
        }
      } catch (error) {
        if (!cancelled) {
          setState({ status: 'unreachable', error })
        }
      }
    }
    void resume()
    return () => {
      cancelled = true
    }
  }, [session, loadUser, attempt])

  useEffect(
    () =>
      session.onEnd(() => {
        // Nothing of the previous user may stay on screen or in the cache.
        queryClient.clear()
        setState({ status: 'signedOut', reason: 'ended' })
      }),
    [session, queryClient],
  )

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      api,
      async signIn(username, password) {
        await session.signIn(username, password)
        await loadUser()
      },
      async signOut() {
        await session.signOut()
        queryClient.clear()
        setState({ status: 'signedOut' })
      },
      retry: () => {
        setState({ status: 'loading' })
        setAttempt((n) => n + 1)
      },
    }),
    [state, api, session, loadUser, queryClient],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}

export function useAuth(): AuthContextValue {
  const context = use(AuthContext)
  if (context === null) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return context
}

/** The signed-in user; only for screens behind <RequireRole>. */
export function useUser(): User {
  const { state } = useAuth()
  if (state.status !== 'signedIn') {
    throw new Error('No user is signed in')
  }
  return state.user
}

export function useApi(): Api {
  return useAuth().api
}

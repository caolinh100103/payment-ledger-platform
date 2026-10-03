import { Link, Navigate, NavLink, Outlet, useLocation } from 'react-router'
import type { ReactNode } from 'react'
import { useAuth, useUser } from '../auth/AuthContext'
import { hasRole, homePath, roleLabels, type Role, type User } from '../auth/roles'
import { ErrorNotice, Loading } from './ui'

interface NavItem {
  to: string
  label: string
}

function navigationFor(user: User): NavItem[] {
  const items: NavItem[] = []
  if (hasRole(user, 'CUSTOMER')) {
    items.push({ to: '/accounts', label: 'Accounts' }, { to: '/transfer', label: 'Send money' },
      { to: '/messages', label: 'Messages' })
  }
  if (hasRole(user, 'ADMIN')) {
    items.push({ to: '/admin/users', label: 'Users' }, { to: '/admin/api-keys', label: 'API keys' })
  }
  if (hasRole(user, 'OPERATOR')) {
    items.push({ to: '/support/customers', label: 'Customers' }, { to: '/support/transfers', label: 'Transfers' })
  }
  if (hasRole(user, 'AUDITOR')) {
    items.push({ to: '/audit', label: 'Audit trail' })
  }
  return items
}

export function Layout() {
  const { signOut } = useAuth()
  const user = useUser()
  return (
    <div className="shell">
      <header className="topbar">
        <div className="topbar__inner">
          <Link to={homePath(user.role)} className="brand">
            <span className="brand__mark" aria-hidden="true">P</span>
            PayLedger
          </Link>
          <nav className="nav" aria-label="Main">
            {navigationFor(user).map((item) => (
              <NavLink key={item.to} to={item.to} className="nav__link">
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="userbox">
            <span className="userbox__name">{user.username}</span>
            <span className="userbox__role">{roleLabels[user.role]}</span>
            <button type="button" className="button button--ghost button--small" onClick={() => void signOut()}>
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}

/** Signed-in screens: waits for the session to resume, and sends anyone else to the sign-in page. */
export function SignedIn({ children }: { children: ReactNode }) {
  const { state, retry } = useAuth()
  const location = useLocation()
  switch (state.status) {
    case 'loading':
      return <FullPage><Loading label="Opening PayLedger…" /></FullPage>
    case 'unreachable':
      return (
        <FullPage>
          <ErrorNotice error={state.error} />
          <button type="button" className="button" onClick={retry}>Try again</button>
        </FullPage>
      )
    case 'signedOut':
      // Only a session that ended by itself (expiry, sign-out in another tab) brings its user back to the same page.
      // After a deliberate sign-out the next person to sign in may be someone else, with another role.
      return (
        <Navigate to="/login" replace
                  state={state.reason === 'ended' ? { from: location.pathname + location.search, reason: 'ended' } : null} />
      )
    case 'signedIn':
      return children
  }
}

/** Sign-in and sign-up: someone signed in goes back to where their session ended, or to their home page. */
export function SignedOut({ children }: { children: ReactNode }) {
  const { state } = useAuth()
  const location = useLocation()
  if (state.status === 'loading') {
    return <FullPage><Loading /></FullPage>
  }
  if (state.status === 'signedIn') {
    const from = (location.state as { from?: string } | null)?.from
    return <Navigate to={from ?? homePath(state.user.role)} replace />
  }
  return children
}

export function RequireRole({ anyOf, children }: { anyOf: Role[]; children: ReactNode }) {
  const user = useUser()
  if (!anyOf.some((role) => hasRole(user, role))) {
    return (
      <div className="empty">
        <strong>This page is not available to your role.</strong>
        <Link to={homePath(user.role)}>Go to your home page</Link>
      </div>
    )
  }
  return children
}

export function HomeRedirect() {
  return <Navigate to={homePath(useUser().role)} replace />
}

export function NotFound() {
  return (
    <div className="empty">
      <strong>Page not found.</strong>
      <Link to="/">Back to the start</Link>
    </div>
  )
}

function FullPage({ children }: { children: ReactNode }) {
  return <div className="full-page">{children}</div>
}

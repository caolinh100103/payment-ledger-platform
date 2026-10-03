import { createBrowserRouter, type RouteObject } from 'react-router'
import { HomeRedirect, Layout, NotFound, RequireRole, SignedIn, SignedOut } from './components/Layout'
import { AccountPage } from './pages/AccountPage'
import { ApiKeysPage } from './pages/admin/ApiKeysPage'
import { UsersPage } from './pages/admin/UsersPage'
import { AuditPage } from './pages/audit/AuditPage'
import { AccountsPage } from './pages/customer/AccountsPage'
import { MessagesPage } from './pages/customer/MessagesPage'
import { TransferPage } from './pages/customer/TransferPage'
import { LoginPage } from './pages/LoginPage'
import { SignupPage } from './pages/SignupPage'
import { CustomersPage } from './pages/support/CustomersPage'
import { TransferLookupPage } from './pages/support/TransferLookupPage'
import { TransferDetailsPage } from './pages/TransferDetailsPage'

export const routes: RouteObject[] = [
  { path: '/login', element: <SignedOut><LoginPage /></SignedOut> },
  { path: '/signup', element: <SignedOut><SignupPage /></SignedOut> },
  {
    element: <SignedIn><Layout /></SignedIn>,
    children: [
      { index: true, element: <HomeRedirect /> },
      // Customers
      { path: 'accounts', element: <RequireRole anyOf={['CUSTOMER']}><AccountsPage /></RequireRole> },
      { path: 'transfer', element: <RequireRole anyOf={['CUSTOMER']}><TransferPage /></RequireRole> },
      { path: 'messages', element: <RequireRole anyOf={['CUSTOMER']}><MessagesPage /></RequireRole> },
      // Customers for their own, operators for anyone's
      { path: 'accounts/:id', element: <RequireRole anyOf={['CUSTOMER', 'OPERATOR']}><AccountPage /></RequireRole> },
      {
        path: 'transfers/:id',
        element: <RequireRole anyOf={['CUSTOMER', 'OPERATOR']}><TransferDetailsPage /></RequireRole>,
      },
      // Back office
      { path: 'support/customers', element: <RequireRole anyOf={['OPERATOR']}><CustomersPage /></RequireRole> },
      { path: 'support/transfers', element: <RequireRole anyOf={['OPERATOR']}><TransferLookupPage /></RequireRole> },
      { path: 'audit', element: <RequireRole anyOf={['AUDITOR']}><AuditPage /></RequireRole> },
      { path: 'admin/users', element: <RequireRole anyOf={['ADMIN']}><UsersPage /></RequireRole> },
      { path: 'admin/api-keys', element: <RequireRole anyOf={['ADMIN']}><ApiKeysPage /></RequireRole> },
      { path: '*', element: <NotFound /> },
    ],
  },
]

export function createRouter() {
  return createBrowserRouter(routes)
}

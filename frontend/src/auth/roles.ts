import type { components } from '../api/generated/core'

export type User = components['schemas']['UserResponse']
export type Role = User['role']

// The same hierarchy as the services: ADMIN implies the back-office roles, but not CUSTOMER, so no staff member can
// move a customer's money. Hiding a screen here is only a convenience; every request is authorized by the server.
const GRANTS: Record<Role, readonly Role[]> = {
  CUSTOMER: ['CUSTOMER'],
  OPERATOR: ['OPERATOR'],
  AUDITOR: ['AUDITOR'],
  ADMIN: ['ADMIN', 'OPERATOR', 'AUDITOR'],
}

export function hasRole(user: Pick<User, 'role'>, role: Role): boolean {
  return GRANTS[user.role].includes(role)
}

/** Where each role lands after signing in. */
export function homePath(role: Role): string {
  switch (role) {
    case 'CUSTOMER':
      return '/accounts'
    case 'OPERATOR':
      return '/support/customers'
    case 'AUDITOR':
      return '/audit'
    case 'ADMIN':
      return '/admin/users'
  }
}

export const roleLabels: Record<Role, string> = {
  CUSTOMER: 'Customer',
  OPERATOR: 'Operator',
  AUDITOR: 'Auditor',
  ADMIN: 'Administrator',
}

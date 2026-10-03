import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../auth/AuthContext'
import { unwrap } from './client'
import type { components as Audit } from './generated/audit'
import type { components as Core } from './generated/core'
import type { components as Notifications } from './generated/notification'

export type Account = Core['schemas']['AccountResponse']
export type Transfer = Core['schemas']['TransferResponse']
export type StatementEntry = Core['schemas']['StatementEntry']
export type ApiKey = Core['schemas']['ApiKey']
export type AuditEvent = Audit['schemas']['AuditEventResponse']
export type ChainVerification = Audit['schemas']['ChainVerification']
export type Notification = Notifications['schemas']['Notification']

const STATEMENT_PAGE = 20
const AUDIT_PAGE = 50

export const keys = {
  accounts: (ownerId?: string) => ['accounts', ownerId ?? 'mine'] as const,
  account: (id: string) => ['account', id] as const,
  statement: (id: string) => ['statement', id] as const,
  transfer: (id: string) => ['transfer', id] as const,
  notifications: (recipientId?: string) => ['notifications', recipientId ?? 'mine'] as const,
  userByName: (username: string) => ['user-by-name', username] as const,
  apiKeys: ['api-keys'] as const,
  auditLatest: (actor: string) => ['audit-latest', actor] as const,
  auditTrail: (resourceId: string) => ['audit-trail', resourceId] as const,
}

/** The caller's own accounts, or a customer's for an operator. */
export function useAccounts(ownerId?: string) {
  const api = useApi()
  return useQuery({
    queryKey: keys.accounts(ownerId),
    queryFn: () => unwrap(api.core.GET('/api/v1/accounts', { params: { query: ownerId ? { ownerId } : {} } })),
  })
}

export function useAccount(id: string) {
  const api = useApi()
  return useQuery({
    queryKey: keys.account(id),
    queryFn: () => unwrap(api.core.GET('/api/v1/accounts/{id}', { params: { path: { id } } })),
  })
}

/** Newest first, a page at a time; each page continues after the last entry of the previous one (a cursor). */
export function useStatement(accountId: string) {
  const api = useApi()
  return useInfiniteQuery({
    queryKey: keys.statement(accountId),
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(api.core.GET('/api/v1/accounts/{id}/ledger-entries', {
        params: { path: { id: accountId }, query: { limit: STATEMENT_PAGE, startingAfter: pageParam } },
      })),
    getNextPageParam: (page) => (page.hasMore ? page.data.at(-1)?.id : undefined),
  })
}

export function useTransfer(id: string) {
  const api = useApi()
  return useQuery({
    queryKey: keys.transfer(id),
    queryFn: () => unwrap(api.core.GET('/api/v1/transfers/{id}', { params: { path: { id } } })),
  })
}

export function useNotifications(recipientId?: string) {
  const api = useApi()
  return useQuery({
    queryKey: keys.notifications(recipientId),
    queryFn: () =>
      unwrap(api.notification.GET('/api/v1/notifications', { params: { query: recipientId ? { recipientId } : {} } })),
  })
}

/** The audit feed, newest first; `actor` (e.g. "user:<id>") narrows it to what one user or key did. */
export function useAuditLatest(actor: string) {
  const api = useApi()
  return useInfiniteQuery({
    queryKey: keys.auditLatest(actor),
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(api.audit.GET('/api/v1/audit-events/latest', {
        params: { query: { actor: actor || undefined, beforeSeq: pageParam, limit: AUDIT_PAGE } },
      })),
    getNextPageParam: (page) => (page.hasMore ? page.data.at(-1)?.seq : undefined),
  })
}

/** The whole story of one transfer, account, user or API key, oldest first. */
export function useAuditTrail(resourceId: string) {
  const api = useApi()
  return useQuery({
    queryKey: keys.auditTrail(resourceId),
    enabled: resourceId !== '',
    queryFn: () =>
      unwrap(api.audit.GET('/api/v1/audit-events', { params: { query: { resourceId } } })),
  })
}

/** After money moved: balances, statements, transfers and messages may all have changed. */
export function useRefreshAfterMovement() {
  const queryClient = useQueryClient()
  return () =>
    Promise.all(
      ['accounts', 'account', 'statement', 'transfer', 'notifications'].map((key) =>
        queryClient.invalidateQueries({ queryKey: [key] }),
      ),
    )
}

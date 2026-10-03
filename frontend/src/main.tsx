import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import { createApi } from './api/client'
import { isApiError } from './api/problem'
import { session } from './api/session'
import { createRouter } from './App'
import { AuthProvider } from './auth/AuthContext'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Reads are retried only when the server said it was not processed (busy, unreachable); a 404 is final.
      retry: (failures, error) => failures < 2 && isApiError(error) && error.retryable,
      staleTime: 10_000,
    },
    // Writes are never retried by the library. Money movements retry on the customer's click, with the same
    // Idempotency-Key.
    mutations: { retry: false },
  },
})

const api = createApi(session, window.location.origin)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider session={session} api={api}>
        <RouterProvider router={createRouter()} />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)

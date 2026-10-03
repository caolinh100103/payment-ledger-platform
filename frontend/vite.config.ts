import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// In development the app is served by Vite and calls the three services through this proxy, so the browser sees a
// single origin, as in production behind nginx (frontend/nginx.conf): no CORS, and the refresh cookie stays
// same-site. The most specific prefixes come first.
const core = process.env.CORE_URL ?? 'http://localhost:8080'
const audit = process.env.AUDIT_URL ?? 'http://localhost:8082'
const notification = process.env.NOTIFICATION_URL ?? 'http://localhost:8083'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/v1/audit-events': { target: audit, xfwd: true },
      '/api/v1/notifications': { target: notification, xfwd: true },
      '/api': { target: core, xfwd: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
})

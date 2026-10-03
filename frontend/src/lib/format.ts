const dateTime = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' })
const dateTimeSeconds = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'medium' })

/** "3 Oct 2026, 15:15" in the viewer's time zone; the API speaks UTC. */
export function formatDateTime(iso: string, options: { seconds?: boolean } = {}): string {
  return (options.seconds ? dateTimeSeconds : dateTime).format(new Date(iso))
}

/**
 * An account as Vietnamese banks print it in their SMS: the last four characters ("...8b10"). The notification
 * service uses the same convention, so the app and the SMS name an account the same way.
 */
export function accountTail(id: string): string {
  return `...${id.slice(-4)}`
}

/** A random UUID (v4) for an Idempotency-Key. crypto.randomUUID needs HTTPS or localhost; this works everywhere. */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

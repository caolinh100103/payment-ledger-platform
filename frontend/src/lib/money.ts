// Amounts travel as integers in the currency's minor unit (ADR 0002): 250000 VND is 250,000 dong, 12345 USD is
// $123.45. They are only ever turned into decimals for display, and a typed amount is parsed as text, digit by
// digit, never through floating point: 0.29 * 100 is 28.999999999999996 in JavaScript.
//
// Every amount the API accepts is at most 10^15 (TransferLimits.MAX_AMOUNT), below 2^53, so it is an exact
// JavaScript number; RFC 7493 (I-JSON) asks for exactly that of integers exchanged in JSON.

export const MAX_AMOUNT = 1_000_000_000_000_000

const SUPPORTED_CURRENCIES = ['VND', 'USD', 'EUR'] as const
export type Currency = (typeof SUPPORTED_CURRENCIES)[number]
export const currencies: readonly Currency[] = SUPPORTED_CURRENCIES

/** ISO 4217 minor unit digits, from the platform's own table (0 for VND, 2 for USD and EUR). */
export function minorDigits(currency: string): number {
  return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2
}

/** 25000000 VND → "25,000,000 VND"; 12345 USD → "123.45 USD". The code after the amount, as on a bank statement. */
export function formatMoney(minor: number, currency: string, options: { signed?: boolean } = {}): string {
  const digits = minorDigits(currency)
  const sign = minor < 0 ? '-' : options.signed && minor > 0 ? '+' : ''
  const abs = Math.abs(minor)
  const scale = 10 ** digits
  const whole = Math.floor(abs / scale)
  const fraction = abs % scale
  const grouped = new Intl.NumberFormat('en-US').format(whole)
  const decimals = digits > 0 ? '.' + String(fraction).padStart(digits, '0') : ''
  return `${sign}${grouped}${decimals} ${currency}`
}

export type ParsedAmount = { ok: true; minor: number } | { ok: false; error: string }

/**
 * Parses what a person typed ("250,000", "1234.5") into minor units. Thousands separators and spaces are ignored;
 * more decimals than the currency has are refused rather than rounded.
 */
export function parseAmount(input: string, currency: string): ParsedAmount {
  const digits = minorDigits(currency)
  const text = input.replace(/[,\s]/g, '')
  if (text === '') {
    return { ok: false, error: 'Enter an amount' }
  }
  const match = /^(\d+)(?:\.(\d*))?$/.exec(text)
  if (!match) {
    return { ok: false, error: 'Use digits only, e.g. 250,000' }
  }
  const whole = match[1] ?? '0'
  const fraction = match[2] ?? ''
  if (fraction.length > digits) {
    return {
      ok: false,
      error: digits === 0 ? `${currency} has no decimals` : `${currency} has at most ${digits} decimals`,
    }
  }
  const minor = BigInt(whole) * 10n ** BigInt(digits) + BigInt(fraction.padEnd(digits, '0') || '0')
  if (minor <= 0n) {
    return { ok: false, error: 'The amount must be more than zero' }
  }
  if (minor > BigInt(MAX_AMOUNT)) {
    return { ok: false, error: 'The amount is too large' }
  }
  return { ok: true, minor: Number(minor) }
}

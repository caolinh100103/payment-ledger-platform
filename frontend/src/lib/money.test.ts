import { describe, expect, it } from 'vitest'
import { formatMoney, parseAmount } from './money'

describe('formatMoney', () => {
  it('formats dong without decimals and cents with two', () => {
    expect(formatMoney(25_000_000, 'VND')).toBe('25,000,000 VND')
    expect(formatMoney(12_345, 'USD')).toBe('123.45 USD')
    expect(formatMoney(5, 'EUR')).toBe('0.05 EUR')
  })

  it('signs movements on request', () => {
    expect(formatMoney(250_000, 'VND', { signed: true })).toBe('+250,000 VND')
    expect(formatMoney(-250_000, 'VND', { signed: true })).toBe('-250,000 VND')
    expect(formatMoney(0, 'VND', { signed: true })).toBe('0 VND')
  })

  it('stays exact up to the largest amount the API accepts', () => {
    expect(formatMoney(1_000_000_000_000_000, 'VND')).toBe('1,000,000,000,000,000 VND')
    expect(formatMoney(999_999_999_999_999, 'USD')).toBe('9,999,999,999,999.99 USD')
  })
})

describe('parseAmount', () => {
  it('reads what people type', () => {
    expect(parseAmount('250,000', 'VND')).toEqual({ ok: true, minor: 250_000 })
    expect(parseAmount(' 1 500 000 ', 'VND')).toEqual({ ok: true, minor: 1_500_000 })
    expect(parseAmount('123.45', 'USD')).toEqual({ ok: true, minor: 12_345 })
    expect(parseAmount('123.4', 'USD')).toEqual({ ok: true, minor: 12_340 })
    expect(parseAmount('10', 'EUR')).toEqual({ ok: true, minor: 1_000 })
  })

  it('never goes through floating point', () => {
    // 0.29 * 100 === 28.999999999999996
    expect(parseAmount('0.29', 'USD')).toEqual({ ok: true, minor: 29 })
    expect(parseAmount('1.15', 'USD')).toEqual({ ok: true, minor: 115 })
  })

  it('refuses decimals the currency does not have instead of rounding them', () => {
    expect(parseAmount('250000.5', 'VND')).toMatchObject({ ok: false, error: 'VND has no decimals' })
    expect(parseAmount('1.005', 'USD')).toMatchObject({ ok: false, error: 'USD has at most 2 decimals' })
  })

  it('refuses nonsense, zero and amounts above the limit', () => {
    expect(parseAmount('', 'VND').ok).toBe(false)
    expect(parseAmount('12a', 'VND').ok).toBe(false)
    expect(parseAmount('-5', 'VND').ok).toBe(false)
    expect(parseAmount('1e6', 'VND').ok).toBe(false)
    expect(parseAmount('0', 'VND').ok).toBe(false)
    expect(parseAmount('0.00', 'USD').ok).toBe(false)
    expect(parseAmount('1000000000000001', 'VND')).toMatchObject({ ok: false, error: 'The amount is too large' })
  })
})

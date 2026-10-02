# ADR 0002: Store money as integer minor units

**Status:** Accepted

## Context

Floating-point types cannot represent most decimal fractions exactly (`0.1 + 0.2 != 0.3`), which
causes rounding drift in balances. `BigDecimal`/`NUMERIC` is exact but slower and easy to misuse
(scale, equals vs compareTo).

## Decision

Every amount is a `long` / `BIGINT` in the currency's smallest unit (VND: dong, USD/EUR: cent),
always paired with an ISO 4217 currency code. Only the presentation layer converts to decimals.

## Consequences

- Arithmetic is exact and fast; overflow is not a concern at realistic amounts (max ~9.2 × 10^18).
- Every API field must document that it is in minor units.
- Currencies with three decimals or none are handled by the currency's fraction digits at the edge.

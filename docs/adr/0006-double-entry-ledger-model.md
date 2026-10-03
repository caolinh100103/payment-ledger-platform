# ADR 0006: Double-entry ledger model

**Status:** Accepted (Phase 2)

## Context

The platform holds customer money, so every balance must be explainable entry by entry, money must never
be created or destroyed by a bug, and mistakes must be corrected without rewriting history.

## Decision

### One sign convention, with SYSTEM accounts as the counterpart

- Every account's balance is **credits minus debits**. A transfer posts exactly one `DEBIT` and one
  `CREDIT` of the same amount.
- **CUSTOMER** accounts are what the platform owes its customers and can never go negative.
- **SYSTEM** accounts are the platform's own side of a movement. The funding account of each currency
  mirrors the money held at the partner bank. A deposit debits it and credits the customer, so the funding
  account goes negative by exactly the amount of customer money in the platform.
- Consequently `SUM(balance)` over all accounts of a currency is **always zero**. Reconciliation then
  amounts to comparing the funding account with the partner bank's statement.
- Customers can never move money out of or into a SYSTEM account through the transfer API: a SYSTEM
  account may go negative, so allowing it would let anyone mint money.

A textbook general ledger would classify the funding account as an asset (debit-normal) and customer
wallets as liabilities (credit-normal). Using one sign convention for every account keeps the arithmetic
and the invariants trivial. Debit-normal and credit-normal presentation can be added in reporting.

### Ledger entries are the source of truth

- `ledger_entries` is **append-only**: triggers reject `UPDATE`, `DELETE` and `TRUNCATE`.
- A **deferred constraint trigger** checks at `COMMIT` that each transfer's entries balance.
- Each entry stores `balance_after`, the running balance like a line on a bank statement, so a statement
  needs no recomputation. Entries are ordered by a sequence id, which gives each account a total order of
  postings.
- `accounts.balance` is a cached projection, updated in the same transaction as the entries. The
  reconciliation queries in `LedgerInvariants` re-derive it from the ledger.

### Every attempt is recorded

- A transfer rejected by a business rule (insufficient funds, frozen account, currency mismatch, wrong
  account type) is stored as `FAILED` with a stable `failure_code` and no entries. Operations and fraud
  monitoring get a trail of rejected attempts, for example repeated attempts against a frozen account.
- Requests that never reached the ledger are not stored: unknown account, malformed payload, same source
  and destination.

### Corrections are compensating entries

- A **reversal** is a new transfer of type `REVERSAL` that moves the same amount in the opposite
  direction and points to the original. The original's entries are untouched; only its status changes
  `COMPLETED → REVERSED`.
- A transfer can be reversed successfully at most once: the original row is locked during a reversal, and a
  partial unique index enforces it at the database level.
- A reversal may debit a **FROZEN** account, because clawing back fraudulent funds is the main reason to
  reverse. It may not touch a **CLOSED** account.
- If the money has already been spent, the reversal is recorded as `FAILED / INSUFFICIENT_FUNDS` and can be
  retried later. Real systems may instead allow a customer balance to go negative and recover the debt.
  That would need an explicit overdraft or receivable model, which is out of scope.

### Transfer state machine

`PENDING → COMPLETED | FAILED`, then `COMPLETED → REVERSED`. Internal movements settle in one
transaction, so `PENDING` is never visible today. It exists for movements that wait on an external rail,
such as a withdrawal to a bank account.

## Consequences

- Any balance can be audited by summing entries, and the invariants can be checked with plain SQL at any
  time (see `LedgerInvariants`, the basis for a daily reconciliation job).
- The `transfers` table grows with rejected attempts too. Partitioning by month is the usual answer when
  that matters.
- Fees, holds (authorised but not captured amounts), partial refunds and multi-currency FX are not modelled
  yet. Each fits the same model as extra SYSTEM accounts and entries.

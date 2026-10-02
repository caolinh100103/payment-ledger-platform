package com.payledger.transfer;

/**
 * {@code PENDING → COMPLETED | FAILED}, then {@code COMPLETED → REVERSED}.
 *
 * <p>Internal movements settle inside one database transaction, so PENDING is never visible after commit.
 * It exists for movements that wait on an external rail (e.g. a withdrawal to a bank account).
 */
public enum TransferStatus {
    PENDING,
    COMPLETED,
    /** Rejected by a business rule. Kept for the audit trail; no ledger entries were posted. */
    FAILED,
    /** Undone by a completed REVERSAL transfer. The original entries stay untouched. */
    REVERSED
}

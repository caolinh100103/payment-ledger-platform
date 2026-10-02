package com.payledger.transfer;

import com.payledger.account.AccountType;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code data} of every transfer event, schema version 1: a snapshot of the transfer at the moment of the
 * event (event-carried state transfer), so consumers never have to call back into the core service.
 * Amounts are in the currency's minor unit (ADR 0002). Documented in {@code docs/events.md}.
 *
 * @param failureCode set on {@code transfer.failed}
 * @param reversalOf  set when this transfer is a REVERSAL: the transfer it undoes
 * @param reversedBy  set on {@code transfer.reversed}: the REVERSAL transfer that undid this one
 */
public record TransferEventData(
        UUID transferId,
        TransferType type,
        TransferStatus status,
        long amount,
        String currency,
        String description,
        AccountRef sourceAccount,
        AccountRef destinationAccount,
        String failureCode,
        String failureReason,
        UUID reversalOf,
        UUID reversedBy,
        Instant createdAt) {

    /**
     * @param balanceAfter the balance right after this event moved money, as on a bank's balance-change
     *                     notification; null when the event moved no money
     */
    public record AccountRef(UUID accountId, String ownerId, AccountType accountType, Long balanceAfter) {
    }
}

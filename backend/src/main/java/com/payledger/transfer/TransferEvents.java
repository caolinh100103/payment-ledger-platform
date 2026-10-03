package com.payledger.transfer;

import com.payledger.account.Account;
import com.payledger.account.AccountLocker.LockedPair;
import com.payledger.outbox.CloudEvent;
import com.payledger.outbox.Outbox;
import com.payledger.transfer.TransferEventData.AccountRef;
import org.springframework.stereotype.Component;

/**
 * Records the transfer lifecycle as CloudEvents in the outbox, inside the transfer's own transaction.
 *
 * <p>Every transfer emits {@code created}, then exactly one of {@code completed} or {@code failed}. A completed
 * transfer that is later undone also emits {@code reversed}; the REVERSAL itself is a transfer with its own
 * lifecycle. This mirrors Stripe's {@code payment_intent.created} → {@code succeeded} / {@code payment_failed},
 * then {@code charge.refunded}. Even though a transfer is decided within one transaction, {@code created} is
 * still published: the audit trail records the request separately from its outcome.
 *
 * <p>All events of a transfer use its id as the Kafka key, so they reach consumers in this order.
 */
@Component
class TransferEvents {

    static final String TOPIC = "payledger.transfers";
    static final String AGGREGATE_TYPE = "transfer";
    static final String SOURCE = "/payledger/core";
    static final int SCHEMA_VERSION = 1;

    static final String CREATED = "com.payledger.transfer.created";
    static final String COMPLETED = "com.payledger.transfer.completed";
    static final String FAILED = "com.payledger.transfer.failed";
    static final String REVERSED = "com.payledger.transfer.reversed";

    private final Outbox outbox;

    TransferEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    /** Before the rules are checked, so the snapshot shows the transfer as requested (PENDING). */
    void created(Transfer transfer, LockedPair accounts) {
        append(CREATED, transfer, transfer.getInitiatedBy(), ref(accounts.source(), false),
                ref(accounts.destination(), false), null);
    }

    /** After posting: the snapshot carries both balances after the movement. */
    void completed(Transfer transfer, LockedPair accounts) {
        append(COMPLETED, transfer, transfer.getInitiatedBy(), ref(accounts.source(), true),
                ref(accounts.destination(), true), null);
    }

    void failed(Transfer transfer, LockedPair accounts) {
        append(FAILED, transfer, transfer.getInitiatedBy(), ref(accounts.source(), false),
                ref(accounts.destination(), false), null);
    }

    /**
     * Emitted for the original transfer, keyed by its id, and attributed to whoever reversed it. The money movement
     * itself (and the new balances) is in the {@code completed} event of {@code reversal}.
     *
     * @param reversalAccounts the accounts locked by the reversal, which runs in the opposite direction
     */
    void reversed(Transfer original, Transfer reversal, LockedPair reversalAccounts) {
        append(REVERSED, original, reversal.getInitiatedBy(), ref(reversalAccounts.destination(), false),
                ref(reversalAccounts.source(), false), reversal);
    }

    private void append(String type, Transfer t, String actor, AccountRef source, AccountRef destination,
                        Transfer reversedBy) {
        TransferEventData data = new TransferEventData(t.getId(), t.getType(), t.getStatus(), t.getAmount(),
                t.getCurrency(), t.getDescription(), source, destination, t.getFailureCode(), t.getFailureReason(),
                t.getReversalOf(), reversedBy == null ? null : reversedBy.getId(), t.getCreatedAt());
        outbox.append(TOPIC, AGGREGATE_TYPE, t.getId(),
                CloudEvent.of(SOURCE, type, SCHEMA_VERSION, t.getId().toString(), actor, data));
    }

    private static AccountRef ref(Account account, boolean withBalance) {
        return new AccountRef(account.getId(), account.getOwnerId(), account.getType(),
                withBalance ? account.getBalance() : null);
    }
}

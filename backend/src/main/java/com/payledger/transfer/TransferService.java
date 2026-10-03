package com.payledger.transfer;

import com.payledger.account.AccountLocker;
import com.payledger.account.AccountLocker.LockedPair;
import com.payledger.account.AccountRepository;
import com.payledger.common.error.BusinessRuleViolationException;
import com.payledger.common.error.ResourceNotFoundException;
import com.payledger.ledger.LedgerService;
import com.payledger.transfer.TransferRules.Rejection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Executes money movements. Each one is a single database transaction (READ COMMITTED + row locks):
 * lock both accounts, check the rules, then either record a FAILED transfer or post the ledger entries,
 * update both balances and record a COMPLETED transfer. The lifecycle events go to the outbox in the same
 * transaction. Nothing is ever half-applied, and no event is ever published for a change that rolled back.
 */
@Service
public class TransferService {

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final AccountLocker locker;
    private final LedgerService ledger;
    private final TransferEvents events;

    public TransferService(TransferRepository transfers, AccountRepository accounts, AccountLocker locker,
                           LedgerService ledger, TransferEvents events) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.locker = locker;
        this.ledger = ledger;
        this.events = events;
    }

    /**
     * Credits a customer with money that has arrived from outside, e.g. after the partner bank confirms a
     * top-up. The other leg debits the funding SYSTEM account of the currency.
     */
    @Transactional
    public Transfer deposit(UUID accountId, long amount, String currency, String description) {
        UUID systemAccountId = accounts.findSystemAccountId(currency)
                .orElseThrow(() -> new BusinessRuleViolationException("UNSUPPORTED_CURRENCY",
                        "Currency " + currency + " is not supported"));
        requireDistinct(systemAccountId, accountId);
        return execute(Transfer.deposit(systemAccountId, accountId, amount, currency, description)).transfer();
    }

    @Transactional
    public Transfer transfer(UUID sourceAccountId, UUID destinationAccountId, long amount, String currency,
                             String description) {
        requireDistinct(sourceAccountId, destinationAccountId);
        return execute(Transfer.transfer(sourceAccountId, destinationAccountId, amount, currency, description))
                .transfer();
    }

    /**
     * Undoes a completed deposit or transfer with a compensating REVERSAL that moves the same amount back.
     * The original transfer and its ledger entries are never modified, only its status becomes REVERSED.
     *
     * <p>The original is locked before the accounts. Concurrent reversals of the same transfer therefore
     * run one after the other and only the first succeeds. Regular transfers never lock transfer rows, so
     * this extra lock cannot create a deadlock cycle.
     *
     * <p>If the account to debit no longer holds the money, the reversal is recorded as FAILED with
     * INSUFFICIENT_FUNDS and the original stays COMPLETED, so it can be retried once funds are available.
     */
    @Transactional
    public Transfer reverse(UUID originalId, String reason) {
        Transfer original = transfers.findByIdForUpdate(originalId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", originalId));
        if (original.getType() == TransferType.REVERSAL) {
            throw new BusinessRuleViolationException("TRANSFER_NOT_REVERSIBLE",
                    "Transfer " + originalId + " is itself a reversal; make a new transfer instead");
        }
        if (original.getStatus() != TransferStatus.COMPLETED) {
            throw new BusinessRuleViolationException("TRANSFER_NOT_REVERSIBLE",
                    "Only COMPLETED transfers can be reversed; transfer " + originalId + " is " + original.getStatus());
        }

        Executed reversal = execute(Transfer.reversalOf(original, reason));
        if (reversal.transfer().getStatus() == TransferStatus.COMPLETED) {
            original.markReversed();
            events.reversed(original, reversal.transfer(), reversal.accounts());
        }
        return reversal.transfer();
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID id) {
        return transfers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Transfer", id));
    }

    private Executed execute(Transfer transfer) {
        LockedPair locked = locker.lock(transfer.getSourceAccountId(), transfer.getDestinationAccountId());
        events.created(transfer, locked);

        Optional<Rejection> rejection = TransferRules.check(transfer, locked.source(), locked.destination());
        if (rejection.isPresent()) {
            transfer.fail(rejection.get().code(), rejection.get().reason());
            transfers.save(transfer);
            events.failed(transfer, locked);
            return new Executed(transfer, locked);
        }

        transfer.complete();
        // Persist the transfer before its entries: they reference it by foreign key.
        transfers.saveAndFlush(transfer);
        ledger.post(transfer.getId(), locked.source(), locked.destination(), transfer.getAmount());
        events.completed(transfer, locked);
        return new Executed(transfer, locked);
    }

    private record Executed(Transfer transfer, LockedPair accounts) {
    }

    private static void requireDistinct(UUID source, UUID destination) {
        if (source.equals(destination)) {
            throw new BusinessRuleViolationException("SAME_ACCOUNT", "Source and destination accounts must differ");
        }
    }
}

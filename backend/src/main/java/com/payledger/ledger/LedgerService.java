package com.payledger.ledger;

import com.payledger.account.Account;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Posts double-entry movements. The only code that changes account balances, and it always does so
 * together with the matching ledger entries, so a balance can always be re-derived from the ledger.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository entries;

    public LedgerService(LedgerEntryRepository entries) {
        this.entries = entries;
    }

    /**
     * Moves {@code amount} from {@code debitAccount} to {@code creditAccount}. Both accounts must already be
     * locked by the caller's transaction, and the transfer row must already be persisted (foreign key).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> post(UUID transferId, Account debitAccount, Account creditAccount, long amount) {
        if (!debitAccount.getCurrency().equals(creditAccount.getCurrency())) {
            throw new IllegalArgumentException("Cannot post between " + debitAccount.getCurrency()
                    + " and " + creditAccount.getCurrency() + " accounts");
        }
        debitAccount.debit(amount);
        LedgerEntry debit = LedgerEntry.of(transferId, debitAccount, EntryDirection.DEBIT, amount);
        creditAccount.credit(amount);
        LedgerEntry credit = LedgerEntry.of(transferId, creditAccount, EntryDirection.CREDIT, amount);
        return entries.saveAll(List.of(debit, credit));
    }
}

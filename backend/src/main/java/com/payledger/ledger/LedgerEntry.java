package com.payledger.ledger;

import com.payledger.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One side of a posting. Immutable: the database also rejects UPDATE and DELETE on this table. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private EntryDirection direction;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    /** Records an entry that has just been applied to {@code account}, capturing its resulting balance. */
    static LedgerEntry of(UUID transferId, Account account, EntryDirection direction, long amount) {
        LedgerEntry entry = new LedgerEntry();
        entry.transferId = transferId;
        entry.accountId = account.getId();
        entry.direction = direction;
        entry.amount = amount;
        entry.currency = account.getCurrency();
        entry.balanceAfter = account.getBalance();
        entry.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return entry;
    }

    public Long getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

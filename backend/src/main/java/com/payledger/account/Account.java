package com.payledger.account;

import com.payledger.common.error.BusinessRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A customer account. Status transitions are enforced here rather than in the service
 * so no caller can put an account into an invalid state.
 *
 * <p>{@code balance} is in the currency's minor unit. It is only changed by the ledger
 * (Phase 2), never directly through the API.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(nullable = false)
    private long balance;

    // Wrapper type: a null version tells Spring Data the entity is new, avoiding a SELECT before INSERT.
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // for JPA
    }

    public static Account open(String ownerId, String currency) {
        Account account = new Account();
        account.id = UUID.randomUUID();
        account.ownerId = ownerId;
        account.currency = currency;
        account.status = AccountStatus.ACTIVE;
        account.balance = 0;
        account.createdAt = now();
        account.updatedAt = account.createdAt;
        return account;
    }

    public void freeze() {
        requireStatus(AccountStatus.ACTIVE, "freeze");
        status = AccountStatus.FROZEN;
    }

    public void unfreeze() {
        requireStatus(AccountStatus.FROZEN, "unfreeze");
        status = AccountStatus.ACTIVE;
    }

    public void close() {
        if (status == AccountStatus.CLOSED) {
            throw invalidTransition("close");
        }
        if (balance != 0) {
            throw new BusinessRuleViolationException("ACCOUNT_BALANCE_NOT_ZERO",
                    "Account " + id + " cannot be closed while its balance is " + balance);
        }
        status = AccountStatus.CLOSED;
    }

    private void requireStatus(AccountStatus expected, String action) {
        if (status != expected) {
            throw invalidTransition(action);
        }
    }

    private BusinessRuleViolationException invalidTransition(String action) {
        return new BusinessRuleViolationException("INVALID_ACCOUNT_STATUS_TRANSITION",
                "Cannot " + action + " account " + id + " in status " + status);
    }

    @PreUpdate
    void touch() {
        updatedAt = now();
    }

    // PostgreSQL stores microseconds; truncating keeps the in-memory value equal to what is persisted.
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

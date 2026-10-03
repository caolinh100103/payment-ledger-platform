package com.payledger.account;

import com.payledger.common.error.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Pessimistically locks the two accounts of a money movement.
 *
 * <p>Locks are always taken in ascending id order. Two concurrent transfers A→B and B→A therefore both
 * try to lock the smaller id first: one waits for the other instead of each holding one lock and waiting
 * for the other's (a deadlock). Any total order works as long as every caller uses the same one; this
 * uses {@link UUID#compareTo}.
 *
 * <p>A lock wait is bounded by {@code lock_timeout} so a stuck transaction makes callers fail fast with a
 * retryable error instead of piling up and exhausting the connection pool.
 */
@Component
public class AccountLocker {

    private final AccountRepository accounts;
    private final EntityManager entityManager;
    private final Duration lockTimeout;

    public AccountLocker(AccountRepository accounts, EntityManager entityManager,
                         @Value("${payledger.ledger.lock-timeout:3s}") Duration lockTimeout) {
        this.accounts = accounts;
        this.entityManager = entityManager;
        this.lockTimeout = lockTimeout;
    }

    /**
     * Must be the first time these accounts are read in the transaction: an entity already in the
     * persistence context would be returned as-is, with a balance read before the lock was taken.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedPair lock(UUID sourceId, UUID destinationId) {
        // SET LOCAL semantics (is_local = true): reverts automatically at the end of the transaction.
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", lockTimeout.toMillis() + "ms")
                .getSingleResult();

        boolean sourceFirst = sourceId.compareTo(destinationId) < 0;
        Account first = lockOne(sourceFirst ? sourceId : destinationId);
        Account second = lockOne(sourceFirst ? destinationId : sourceId);
        return sourceFirst ? new LockedPair(first, second) : new LockedPair(second, first);
    }

    private Account lockOne(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    public record LockedPair(Account source, Account destination) {
    }
}

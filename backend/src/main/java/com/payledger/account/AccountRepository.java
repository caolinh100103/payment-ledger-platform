package com.payledger.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByOwnerIdOrderByCreatedAtAsc(String ownerId);

    /**
     * Row lock for a balance change. {@code FOR NO KEY UPDATE} is the same lock an {@code UPDATE} of a
     * non-key column takes, so it serialises balance changes without blocking inserts elsewhere that only
     * reference the account through a foreign key (those take {@code FOR KEY SHARE}).
     */
    @Query(value = "SELECT * FROM accounts WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Account> findByIdForUpdate(UUID id);

    @Query("select a.id from Account a where a.type = com.payledger.account.AccountType.SYSTEM and a.currency = :currency")
    Optional<UUID> findSystemAccountId(String currency);

    /**
     * Only the owner, not the entity: an authorization check before {@link AccountLocker#lock} must not put the
     * account into the persistence context, or the lock would return that stale copy.
     */
    @Query("select a.ownerId from Account a where a.id = :id")
    Optional<String> findOwnerId(UUID id);
}

package com.payledger.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    /** Serialises concurrent reversals of the same transfer. */
    @Query(value = "SELECT * FROM transfers WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Transfer> findByIdForUpdate(UUID id);
}

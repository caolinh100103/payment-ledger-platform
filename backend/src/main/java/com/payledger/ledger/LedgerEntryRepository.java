package com.payledger.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransferIdOrderById(UUID transferId);

    List<LedgerEntry> findByAccountIdOrderById(UUID accountId);
}

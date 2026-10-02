package com.payledger.audit;

import java.time.Instant;

/** One row of the audit log: the event, its position in the chain and the hashes that link it. */
public record AuditRecord(long seq, AuditableEvent event, Instant recordedAt, String prevHash, String hash) {

    /** Recomputes the hash from the stored fields; differs from {@link #hash()} if the row was altered. */
    String expectedHash() {
        return AuditChain.hash(seq, event, recordedAt, prevHash);
    }
}

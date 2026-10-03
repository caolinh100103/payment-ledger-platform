package com.payledger.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes keys past their retention window. Expired keys are already ignored when claiming, so this only
 * bounds the table size. Small batches keep each DELETE short; running it on several instances is harmless.
 */
@Component
class IdempotencyKeyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanup.class);
    private static final int BATCH_SIZE = 1_000;

    private final IdempotencyStore store;

    IdempotencyKeyCleanup(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(initialDelayString = "${payledger.idempotency.cleanup-interval:PT10M}",
            fixedDelayString = "${payledger.idempotency.cleanup-interval:PT10M}")
    void deleteExpiredKeys() {
        int total = 0;
        int deleted;
        do {
            deleted = store.deleteExpired(BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            log.info("Deleted {} expired idempotency keys", total);
        }
    }
}

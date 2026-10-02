package com.payledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Deletes published events after the retention window. Pending events are never deleted, however old.
 * Small batches keep each DELETE short; running it on several instances is harmless.
 */
@Component
class OutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);
    private static final int BATCH_SIZE = 1_000;

    private final JdbcTemplate jdbc;
    private final Duration retention;

    OutboxCleanup(JdbcTemplate jdbc, @Value("${payledger.outbox.retention:7d}") Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "${payledger.outbox.cleanup-interval:PT10M}",
            fixedDelayString = "${payledger.outbox.cleanup-interval:PT10M}")
    void deletePublishedEvents() {
        int total = 0;
        int deleted;
        do {
            deleted = deleteBatch();
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            log.info("Deleted {} published outbox events older than {}", total, retention);
        }
    }

    int deleteBatch() {
        return jdbc.update("""
                DELETE FROM outbox
                WHERE id IN (SELECT id FROM outbox
                             WHERE published_at < now() - make_interval(secs => ?)
                             LIMIT ?)
                """, retention.toSeconds(), BATCH_SIZE);
    }
}

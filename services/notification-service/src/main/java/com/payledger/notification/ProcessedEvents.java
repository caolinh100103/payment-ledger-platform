package com.payledger.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/** Remembers which events a consumer has already acted on (the idempotent consumer pattern). */
@Repository
class ProcessedEvents {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEvents.class);
    private static final int BATCH_SIZE = 1_000;

    private final JdbcTemplate jdbc;
    private final Duration retention;

    ProcessedEvents(JdbcTemplate jdbc, @Value("${payledger.processed-events.retention:14d}") Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /**
     * Claims {@code eventId} for {@code consumer}. Must run in the transaction that performs the side effect.
     *
     * <p>If another delivery of the same event is being processed right now, the insert waits for that
     * transaction: if it commits, this returns false; if it rolls back, this claim succeeds instead. So two
     * concurrent deliveries can never both act.
     *
     * @return true on the first delivery; false if the event was already processed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.update("""
                INSERT INTO processed_events (consumer, event_id) VALUES (?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, consumer, eventId) == 1;
    }

    /**
     * Keeps the table bounded. The retention must exceed the topic's retention (Kafka's default is 7 days):
     * forgetting an event that Kafka can still redeliver would let it be processed twice.
     */
    @Scheduled(initialDelayString = "${payledger.processed-events.cleanup-interval:PT1H}",
            fixedDelayString = "${payledger.processed-events.cleanup-interval:PT1H}")
    void deleteExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("""
                    DELETE FROM processed_events
                    WHERE ctid IN (SELECT ctid FROM processed_events
                                   WHERE processed_at < now() - make_interval(secs => ?) LIMIT ?)
                    """, retention.toSeconds(), BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            log.info("Deleted {} processed-event records older than {}", total, retention);
        }
    }
}

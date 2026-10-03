package com.payledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The outbox backlog, the health signal of the relay:
 *
 * <ul>
 *   <li>{@code payledger_outbox_pending}: events not yet acknowledged by Kafka.</li>
 *   <li>{@code payledger_outbox_oldest_pending_age_seconds}: how long the oldest of them has waited, 0 when none
 *       has. This is what to alert on: a backlog that is large but moving is fine, one that is old is not (Kafka
 *       down, a poison event holding back its aggregate, the relay stuck).</li>
 * </ul>
 *
 * <p>The values are read by a scheduled query rather than on every scrape, so a slow database never makes a scrape
 * hang. When the query fails they become NaN ("unknown") instead of repeating a stale number. Every instance reports
 * the same global values; dashboards take the max.
 */
@Component
class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final JdbcTemplate jdbc;
    private volatile double pending = Double.NaN;
    private volatile double oldestPendingAgeSeconds = Double.NaN;

    OutboxMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder("payledger.outbox.pending", this, metrics -> metrics.pending)
                .description("Outbox events not yet published to Kafka")
                .register(meters);
        Gauge.builder("payledger.outbox.oldest.pending.age", this, metrics -> metrics.oldestPendingAgeSeconds)
                .description("How long the oldest unpublished outbox event has waited")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${payledger.outbox.metrics-interval:PT10S}")
    void refresh() {
        try {
            // Both use the partial index on pending rows. Ids are assigned in insertion order, so the lowest pending
            // id is the oldest event. The age uses the database clock, like created_at.
            pending = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
            oldestPendingAgeSeconds = jdbc.query("""
                    SELECT extract(epoch FROM clock_timestamp() - created_at)
                    FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 1
                    """, (rs, row) -> rs.getDouble(1)).stream().findFirst().orElse(0.0);
        } catch (DataAccessException e) {
            pending = Double.NaN;
            oldestPendingAgeSeconds = Double.NaN;
            log.warn("Could not read the outbox backlog: {}", e.toString());
        }
    }
}

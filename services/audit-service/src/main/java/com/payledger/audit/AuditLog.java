package com.payledger.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The audit log table. Rows are only ever inserted (the database rejects anything else, see V1). */
@Repository
public class AuditLog {

    // Any constant works: it only has to be the same for every instance of the service.
    private static final long CHAIN_LOCK = 0x4155_4449_54L; // "AUDIT"
    private static final int STREAM_FETCH_SIZE = 500;

    private static final RowMapper<AuditRecord> ROW = (rs, row) -> new AuditRecord(
            rs.getLong("seq"),
            new AuditableEvent(rs.getObject("event_id", UUID.class), rs.getString("actor"), rs.getString("action"),
                    rs.getString("resource_id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                    rs.getString("source"), rs.getString("payload")),
            rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
            rs.getString("prev_hash"),
            rs.getString("hash"));

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends {@code event} as the new head of the chain.
     *
     * <p>Appends are serialised with a transaction-scoped advisory lock: the chain is a single sequence, so two
     * consumers must never both read the same head and link to it. After the lock is granted, READ COMMITTED
     * gives the next statement a fresh snapshot, so it sees the head the previous holder just committed.
     *
     * @return false if the event was already recorded (a redelivery); nothing is written then
     */
    @Transactional
    public boolean append(AuditableEvent event) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(?)", Integer.class, CHAIN_LOCK);

        if (jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM audit_events WHERE event_id = ?)", Boolean.class,
                event.eventId())) {
            return false;
        }

        Optional<AuditRecord> head = jdbc.query("SELECT * FROM audit_events ORDER BY seq DESC LIMIT 1", ROW)
                .stream().findFirst();
        long seq = head.map(AuditRecord::seq).orElse(0L) + 1;
        String prevHash = head.map(AuditRecord::hash).orElse(AuditChain.GENESIS_HASH);
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String hash = AuditChain.hash(seq, event, recordedAt, prevHash);

        jdbc.update("""
                        INSERT INTO audit_events (seq, event_id, actor, action, resource_id, occurred_at, source, payload,
                                                  recorded_at, prev_hash, hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, seq, event.eventId(), event.actor(), event.action(), event.resourceId(),
                event.occurredAt().atOffset(ZoneOffset.UTC), event.source(), event.payload(), recordedAt.atOffset(ZoneOffset.UTC),
                prevHash, hash);
        return true;
    }

    @Transactional(readOnly = true)
    public List<AuditRecord> findByResource(String resourceId, int limit) {
        return jdbc.query("SELECT * FROM audit_events WHERE resource_id = ? ORDER BY seq LIMIT ?", ROW, resourceId,
                limit);
    }

    /**
     * Walks the whole chain in order. Runs in a read-only transaction so PostgreSQL streams the rows through a
     * cursor ({@code fetchSize}) instead of loading the whole log into memory.
     */
    @Transactional(readOnly = true)
    public ChainVerification verify() {
        ChainVerification.Walker walker = new ChainVerification.Walker();
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT * FROM audit_events ORDER BY seq");
            statement.setFetchSize(STREAM_FETCH_SIZE);
            return statement;
        }, (RowCallbackHandler) rs -> walker.accept(ROW.mapRow(rs, 0)));
        return walker.result();
    }
}

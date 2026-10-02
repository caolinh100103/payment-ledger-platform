package com.payledger.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.payledger.audit.AuditTestEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit log against a real PostgreSQL: the chain, and each of the three tamper-protection layers.
 * Tests that tamper with the log restore it afterwards, so the chain stays valid for the other tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditLogIntegrationTest {

    @Autowired
    AuditLog auditLog;

    /** Connected as the application role, audit_app. */
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void linksEachRecordToThePreviousOne() {
        String transfer = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            assertThat(auditLog.append(event(transfer))).isTrue();
        }

        List<AuditRecord> records = auditLog.findByResource(transfer, 10);

        assertThat(records).hasSize(3);
        for (int i = 1; i < records.size(); i++) {
            assertThat(records.get(i).seq()).isEqualTo(records.get(i - 1).seq() + 1);
            assertThat(records.get(i).prevHash()).isEqualTo(records.get(i - 1).hash());
        }
        assertThat(auditLog.verify().valid()).isTrue();
    }

    @Test
    void firstRecordLinksToTheGenesisHash() {
        auditLog.append(event(UUID.randomUUID().toString()));

        assertThat(jdbc.queryForObject("SELECT prev_hash FROM audit_events WHERE seq = 1", String.class))
                .isEqualTo("0".repeat(64));
    }

    @Test
    void redeliveredEventIsRecordedOnce() {
        AuditableEvent event = event(UUID.randomUUID().toString());

        assertThat(auditLog.append(event)).isTrue();
        assertThat(auditLog.append(event)).isFalse();

        assertThat(auditLog.findByResource(event.resourceId(), 10)).hasSize(1);
    }

    @Test
    void concurrentConsumersStillBuildOneUnbrokenChain() throws Exception {
        long before = count();
        int threads = 8;
        int perThread = 25;

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> appends = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                appends.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        auditLog.append(event(UUID.randomUUID().toString()));
                    }
                }));
            }
            for (Future<?> append : appends) {
                append.get();
            }
        }

        assertThat(count()).isEqualTo(before + threads * perThread);
        ChainVerification verification = auditLog.verify();
        assertThat(verification.valid()).as(verification.problem()).isTrue();
        assertThat(verification.checkedEvents()).isEqualTo(count());
    }

    @Test
    void applicationRoleCannotChangeOrRemoveRecords() {
        auditLog.append(event(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET actor = 'mallory'"))
                .rootCause().hasMessageContaining("permission denied for table audit_events");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events"))
                .rootCause().hasMessageContaining("permission denied for table audit_events");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_events"))
                .rootCause().hasMessageContaining("permission denied for table audit_events");
    }

    @Test
    void ownerIsStoppedByTheAppendOnlyTriggers() throws SQLException {
        auditLog.append(event(UUID.randomUUID().toString()));

        try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE audit_events SET actor = 'mallory'"))
                    .hasMessageContaining("audit_events is append-only: UPDATE");
            assertThatThrownBy(() -> sql.executeUpdate("DELETE FROM audit_events"))
                    .hasMessageContaining("audit_events is append-only: DELETE");
            assertThatThrownBy(() -> sql.execute("TRUNCATE audit_events"))
                    .hasMessageContaining("audit_events is append-only: TRUNCATE");
        }
    }

    @Test
    void editByASuperuserWhoDisabledTheTriggersIsDetected() throws SQLException {
        long seq = appendAndGetSeq();

        ChainVerification verification = verifyAfterTampering(seq, """
                UPDATE audit_events SET payload = replace(payload, '"amount":250000', '"amount":1') WHERE seq = %d
                """.formatted(seq));

        assertThat(verification.valid()).isFalse();
        assertThat(verification.firstInvalidSeq()).isEqualTo(seq);
        assertThat(verification.problem()).isEqualTo("content does not match its hash");
    }

    @Test
    void editWithARecomputedHashBreaksTheNextLink() throws SQLException {
        long seq = appendAndGetSeq();
        auditLog.append(event(UUID.randomUUID().toString()));
        AuditRecord original = recordAt(seq);
        AuditableEvent forged = new AuditableEvent(original.event().eventId(), "mallory", original.event().action(),
                original.event().resourceId(), original.event().occurredAt(), original.event().source(),
                original.event().payload());
        String forgedHash = AuditChain.hash(seq, forged, original.recordedAt(), original.prevHash());

        ChainVerification verification = verifyAfterTampering(seq,
                "UPDATE audit_events SET actor = 'mallory', hash = '%s' WHERE seq = %d".formatted(forgedHash, seq));

        // The forged record is consistent with itself, but its successor still points at the original hash.
        assertThat(verification.valid()).isFalse();
        assertThat(verification.firstInvalidSeq()).isEqualTo(seq + 1);
        assertThat(verification.problem()).isEqualTo("prev_hash does not match the hash of record " + seq);
    }

    @Test
    void deletedRecordShowsUpAsAGap() throws SQLException {
        long seq = appendAndGetSeq();
        auditLog.append(event(UUID.randomUUID().toString()));

        ChainVerification verification = verifyAfterTampering(seq, "DELETE FROM audit_events WHERE seq = " + seq);

        assertThat(verification.valid()).isFalse();
        assertThat(verification.firstInvalidSeq()).isEqualTo(seq);
        assertThat(verification.problem()).startsWith("record " + seq + " is missing");
    }

    /**
     * Runs {@code tampering} as a superuser who switched triggers off, verifies the chain, then puts the original
     * row back so the other tests see a valid chain.
     */
    private ChainVerification verifyAfterTampering(long seq, String tampering) throws SQLException {
        ChainVerification verification;
        try (Connection superuser = ownerConnection(); Statement sql = superuser.createStatement()) {
            // Session-level switch that skips ordinary triggers. Only a superuser can set it.
            sql.execute("SET session_replication_role = replica");
            sql.execute("CREATE TEMP TABLE original AS SELECT * FROM audit_events WHERE seq = " + seq);
            sql.executeUpdate(tampering);
            try {
                verification = auditLog.verify();
            } finally {
                sql.executeUpdate("DELETE FROM audit_events WHERE seq = " + seq);
                sql.executeUpdate("INSERT INTO audit_events SELECT * FROM original");
            }
        }
        assertThat(auditLog.verify().valid()).as("chain restored").isTrue();
        return verification;
    }

    private long appendAndGetSeq() {
        AuditableEvent event = event(UUID.randomUUID().toString());
        auditLog.append(event);
        return auditLog.findByResource(event.resourceId(), 1).getFirst().seq();
    }

    private AuditRecord recordAt(long seq) {
        String resourceId = jdbc.queryForObject("SELECT resource_id FROM audit_events WHERE seq = ?", String.class, seq);
        return auditLog.findByResource(resourceId, 10).stream().filter(r -> r.seq() == seq).findFirst().orElseThrow();
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events", Long.class);
    }

    /** The schema owner, which in this container is also a superuser. */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}

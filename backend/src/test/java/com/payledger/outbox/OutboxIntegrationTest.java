package com.payledger.outbox;

import com.jayway.jsonpath.JsonPath;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The outbox only ever holds events of committed transactions. */
class OutboxIntegrationTest extends ApiTestSupport {

    @Autowired
    Outbox outbox;

    @Autowired
    TransactionTemplate tx;

    @Test
    void storesTheEnvelopeAsPendingForTheRelay() {
        UUID aggregateId = UUID.randomUUID();
        CloudEvent<Map<String, Object>> event = sampleEvent(aggregateId);

        tx.executeWithoutResult(status -> outbox.append("test.topic", "test", aggregateId, event));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_id::text AS aggregate_id, event_type, topic, payload::text AS payload,
                       published_at, attempts
                FROM outbox WHERE event_id = ?
                """, event.id());
        assertThat(row).containsEntry("aggregate_type", "test")
                .containsEntry("aggregate_id", aggregateId.toString())
                .containsEntry("event_type", "com.payledger.test.happened")
                .containsEntry("topic", "test.topic")
                .containsEntry("attempts", 0)
                .containsEntry("published_at", null);

        String payload = (String) row.get("payload");
        assertThat(JsonPath.<String>read(payload, "$.specversion")).isEqualTo("1.0");
        assertThat(JsonPath.<String>read(payload, "$.id")).isEqualTo(event.id().toString());
        assertThat(JsonPath.<String>read(payload, "$.subject")).isEqualTo(aggregateId.toString());
        assertThat(JsonPath.<String>read(payload, "$.time")).isEqualTo(event.time().toString());
        assertThat(JsonPath.<Integer>read(payload, "$.schemaversion")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(payload, "$.data.amount")).isEqualTo(250_000);
    }

    @Test
    void eventOfARolledBackTransactionIsNeverRecorded() {
        UUID aggregateId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            outbox.append("test.topic", "test", aggregateId, sampleEvent(aggregateId));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, aggregateId))
                .isZero();
    }

    @Test
    void refusesToWriteOutsideTheBusinessTransaction() {
        UUID aggregateId = UUID.randomUUID();

        assertThatThrownBy(() -> outbox.append("test.topic", "test", aggregateId, sampleEvent(aggregateId)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private static CloudEvent<Map<String, Object>> sampleEvent(UUID aggregateId) {
        return CloudEvent.of("/payledger/test", "com.payledger.test.happened", 1, aggregateId.toString(), "tester",
                Map.of("amount", 250_000));
    }
}

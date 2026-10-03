package com.payledger.audit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditableEventTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void extractsTheAuditFieldsAndKeepsTheMessageVerbatim() {
        UUID id = UUID.randomUUID();
        String message = """
                {"specversion":"1.0","id":"%s","source":"/payledger/core","type":"com.payledger.transfer.failed",
                 "subject":"t-1","time":"2026-10-03T15:15:30.1234567+07:00","actor":"alice","schemaversion":7,
                 "data":{"anything":"goes"}}""".formatted(id);

        AuditableEvent event = AuditableEvent.parse(message, json);

        assertThat(event.eventId()).isEqualTo(id);
        assertThat(event.action()).isEqualTo("com.payledger.transfer.failed");
        assertThat(event.resourceId()).isEqualTo("t-1");
        assertThat(event.actor()).isEqualTo("alice");
        // Normalised to UTC and truncated to the microseconds PostgreSQL stores.
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-03T08:15:30.123456Z"));
        assertThat(event.payload()).isEqualTo(message);
    }

    @Test
    void missingActorIsRecordedAsUnknown() {
        AuditableEvent event = AuditableEvent.parse("""
                {"specversion":"1.0","id":"%s","source":"/x","type":"t","time":"2026-10-03T08:15:30Z"}
                """.formatted(UUID.randomUUID()), json);

        assertThat(event.actor()).isEqualTo("unknown");
        assertThat(event.resourceId()).isNull();
    }

    @Test
    void rejectsMessagesThatAreNotCloudEvents() {
        String valid = """
                {"specversion":"1.0","id":"%s","source":"/x","type":"t","time":"2026-10-03T08:15:30Z"}
                """.formatted(UUID.randomUUID());

        assertThatThrownBy(() -> AuditableEvent.parse("not json", json)).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> AuditableEvent.parse("[1, 2]", json)).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> AuditableEvent.parse(valid.replace("1.0", "0.3"), json))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("specversion");
        assertThatThrownBy(() -> AuditableEvent.parse(valid.replace("\"type\":\"t\",", ""), json))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("'type'");
        assertThatThrownBy(() -> AuditableEvent.parse(valid.replaceFirst("\"id\":\"[^\"]+\"", "\"id\":\"42\""), json))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> AuditableEvent.parse(valid.replace("2026-10-03T08:15:30Z", "yesterday"), json))
                .isInstanceOf(MalformedEventException.class);
    }
}

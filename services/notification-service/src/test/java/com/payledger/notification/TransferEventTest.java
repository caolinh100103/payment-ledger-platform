package com.payledger.notification;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferEventTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void ignoresFieldsItDoesNotKnow() {
        String message = TestEvents.completedTransfer(UUID.randomUUID(), "Rent")
                .replace("\"actor\":\"anonymous\"", "\"actor\":\"anonymous\",\"traceparent\":\"00-abc\"")
                .replace("\"currency\":\"VND\"", "\"currency\":\"VND\",\"fee\":{\"amount\":0}");

        TransferEvent event = TransferEvent.parse(message, json);

        assertThat(event.data().amount()).isEqualTo(250_000);
        assertThat(event.data().sourceAccount().ownerId()).isEqualTo("alice");
    }

    @Test
    void rejectsASchemaVersionItWasNotWrittenFor() {
        String v2 = TestEvents.event(UUID.randomUUID(), "com.payledger.transfer.completed", 2, "{}");

        assertThatThrownBy(() -> TransferEvent.parse(v2, json))
                .isInstanceOf(UnsupportedEventException.class)
                .hasMessageContaining("schemaversion 2");
    }

    @Test
    void rejectsMessagesThatAreNotTransferCloudEvents() {
        assertThatThrownBy(() -> TransferEvent.parse("not json", json)).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> TransferEvent.parse("{\"id\":\"%s\"}".formatted(UUID.randomUUID()), json))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> TransferEvent.parse(TestEvents.event(UUID.randomUUID(),
                "com.payledger.transfer.completed", 1, "null"), json))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("no data");
    }
}

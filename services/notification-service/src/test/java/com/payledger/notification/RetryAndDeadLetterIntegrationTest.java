package com.payledger.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.time.Duration;
import java.util.UUID;

import static com.payledger.notification.TestcontainersConfiguration.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Non-blocking retries through the retry topics, and the dead-letter topic at the end. */
class RetryAndDeadLetterIntegrationTest extends ConsumerTestSupport {

    @Test
    void transientFailureIsRetriedUntilItSucceeds() throws Exception {
        UUID eventId = UUID.randomUUID();
        IllegalStateException timeout = new IllegalStateException("SMS gateway timed out");
        doThrow(timeout).doThrow(timeout).doCallRealMethod()
                .when(sender).send(argThat(n -> n.eventId().equals(eventId)));

        kafka.send(TOPIC, "transfer-retry", TestEvents.completedTransfer(eventId, "Rent")).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> store.findByEvent(eventId).size() == 2);
        // Attempts 1 and 2 failed on the first message; attempt 3 (from retry topic 1) sent both.
        verify(sender, times(4)).send(argThat(n -> n.eventId().equals(eventId)));
    }

    @Test
    void eventThatKeepsFailingIsParkedOnTheDeadLetterTopic() throws Exception {
        UUID eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("SMS gateway down"))
                .when(sender).send(argThat(n -> n.eventId().equals(eventId)));

        kafka.send(TOPIC, "transfer-dlt", TestEvents.completedTransfer(eventId, "Rent")).get();

        ConsumerRecord<String, String> parked = awaitDeadLetter(eventId);
        assertThat(parked.key()).isEqualTo("transfer-dlt");
        assertThat(header(parked, KafkaHeaders.ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(header(parked, KafkaHeaders.EXCEPTION_CAUSE_FQCN)).isEqualTo(IllegalStateException.class.getName());
        assertThat(header(parked, KafkaHeaders.EXCEPTION_MESSAGE)).contains("SMS gateway down");
        // 1 attempt on the main topic + 3 retries, each failing on its first message.
        assertThat(processingAttempts(parked)).isEqualTo(4);
        verify(sender, times(4)).send(argThat(n -> n.eventId().equals(eventId)));
        assertThat(store.findByEvent(eventId)).isEmpty();
    }

    @Test
    void malformedEventIsParkedWithoutRetrying() {
        UUID eventId = UUID.randomUUID();
        String truncated = TestEvents.completedTransfer(eventId, "Rent").substring(0, 120);
        assertThat(truncated).contains(eventId.toString());

        kafka.send(TOPIC, "transfer-poison", truncated);

        ConsumerRecord<String, String> parked = awaitDeadLetter(eventId);
        assertThat(header(parked, KafkaHeaders.EXCEPTION_CAUSE_FQCN)).isEqualTo(MalformedEventException.class.getName());
        // Parked straight from the main topic: no retry topic ever saw it.
        assertThat(processingAttempts(parked)).isOne();
        assertThat(parked.value()).isEqualTo(truncated);
    }

    @Test
    void unsupportedSchemaVersionIsParkedWithoutRetrying() {
        UUID eventId = UUID.randomUUID();
        String v2 = TestEvents.event(eventId, "com.payledger.transfer.completed", 2, "{\"amount\":{\"value\":\"2500\"}}");

        kafka.send(TOPIC, "transfer-v2", v2);

        ConsumerRecord<String, String> parked = awaitDeadLetter(eventId);
        assertThat(header(parked, KafkaHeaders.EXCEPTION_CAUSE_FQCN))
                .isEqualTo(UnsupportedEventException.class.getName());
        assertThat(processingAttempts(parked)).isOne();
        verify(sender, never()).send(argThat(n -> n.eventId().equals(eventId)));
    }
}

package com.payledger.audit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.payledger.audit.AuditTestEvents.cloudEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The audit trail rides out a database outage, and parks what it can never record. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditRetryIntegrationTest {

    private static final String TOPIC = "payledger.transfers";
    private static final String DEAD_LETTER_TOPIC = TOPIC + "-audit-dlt";

    @MockitoSpyBean
    AuditLog auditLog;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    MeterRegistry meters;

    @Test
    void eventIsRecordedOnceTheDatabaseIsBack() throws Exception {
        UUID eventId = UUID.randomUUID();
        String transfer = UUID.randomUUID().toString();
        TransientDataAccessResourceException outage = new TransientDataAccessResourceException("connection refused");
        doThrow(outage).doThrow(outage).doCallRealMethod()
                .when(auditLog).append(argThat(e -> e.eventId().equals(eventId)));

        kafka.send(TOPIC, transfer, cloudEvent(eventId, "com.payledger.transfer.completed", transfer)).get();

        // Main topic, then retry-0 (after 2 s) fail; retry-1 (after 6 s) succeeds.
        await().atMost(Duration.ofSeconds(30)).until(() -> auditLog.findByResource(transfer, 10).size() == 1);
        verify(auditLog, times(3)).append(argThat(e -> e.eventId().equals(eventId)));
        assertThat(auditLog.verify().valid()).isTrue();
    }

    @Test
    void messageThatIsNotACloudEventIsParkedWithoutRetrying() {
        UUID marker = UUID.randomUUID();
        double parkedBefore = deadLettered();

        kafka.send(TOPIC, "garbage", "not a CloudEvent " + marker);

        ConsumerRecord<String, String> parked = awaitDeadLetter(marker);
        assertThat(header(parked, KafkaHeaders.EXCEPTION_CAUSE_FQCN)).isEqualTo(MalformedEventException.class.getName());
        // The attempts header also counts the forward to the dead-letter topic: 2 means processed once.
        assertThat(ByteBuffer.wrap(parked.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS).value())
                .getInt()).isEqualTo(2);
        // The counter the AUDIT GAP alert fires on, tagged with the topic the event was meant for.
        await().atMost(Duration.ofSeconds(10)).until(() -> deadLettered() == parkedBefore + 1);
    }

    private double deadLettered() {
        Counter counter = meters.find("payledger.events.dead.lettered").tag("topic", TOPIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private ConsumerRecord<String, String> awaitDeadLetter(UUID marker) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(DEAD_LETTER_TOPIC).stream()
                    .map(info -> new TopicPartition(DEAD_LETTER_TOPIC, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (record.value().contains(marker.toString())) {
                        found.set(record);
                    }
                });
                return found.get() != null;
            });
            return found.get();
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}

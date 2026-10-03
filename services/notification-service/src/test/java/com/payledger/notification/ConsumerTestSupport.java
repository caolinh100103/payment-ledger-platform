package com.payledger.notification;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.reset;

/**
 * Shared Spring context for the consumer tests: real Kafka and PostgreSQL, with the sender spied on so a test can
 * make delivery fail. Stubs should match a single event id, because events of earlier tests may still be retrying.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class ConsumerTestSupport {

    static final String DEAD_LETTER_TOPIC = TestcontainersConfiguration.TOPIC + "-notification-dlt";

    @Autowired
    TransferNotifier notifier;

    @Autowired
    NotificationStore store;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @MockitoSpyBean
    NotificationSender sender;

    @BeforeEach
    void resetSender() {
        reset(sender);
    }

    /** Waits for the dead-letter record whose value mentions {@code eventId}. */
    ConsumerRecord<String, String> awaitDeadLetter(UUID eventId) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(DEAD_LETTER_TOPIC).stream()
                    .map(info -> new TopicPartition(DEAD_LETTER_TOPIC, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(45)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (record.value() != null && record.value().contains(eventId.toString())) {
                        found.set(record);
                    }
                });
                return found.get() != null;
            });
            return found.get();
        }
    }

    /** How many times the event was processed before it was parked (1 = never retried). */
    static int processingAttempts(ConsumerRecord<?, ?> record) {
        // The header is incremented on every forward, including the last one to the dead-letter topic.
        return ByteBuffer.wrap(record.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS).value()).getInt()
                - 1;
    }

    static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}

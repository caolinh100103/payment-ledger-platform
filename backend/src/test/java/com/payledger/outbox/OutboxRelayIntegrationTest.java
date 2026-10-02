package com.payledger.outbox;

import com.jayway.jsonpath.JsonPath;
import com.payledger.TestcontainersConfiguration;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives {@link OutboxRelay} by hand against a real broker (the scheduled relay is off in this context), so
 * each test controls exactly when and by how many relay instances events are published.
 */
@SpringBootTest(properties = "payledger.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class OutboxRelayIntegrationTest {

    @Autowired
    OutboxRelay relay;

    @Autowired
    Outbox outbox;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaContainer kafka;

    @AfterEach
    void discardUnpublishableEvents() {
        jdbc.update("UPDATE outbox SET published_at = now() WHERE published_at IS NULL");
    }

    @Test
    void publishesKeyedCloudEventsAndMarksThemPublished() {
        String topic = createTopic();
        UUID transferA = UUID.randomUUID();
        UUID transferB = UUID.randomUUID();
        List<UUID> eventIds = List.of(append(topic, transferA, 0), append(topic, transferA, 1),
                append(topic, transferB, 0));

        assertThat(drain()).isEqualTo(3);

        List<ConsumerRecord<String, String>> records = consume(topic, 3);
        assertThat(records).extracting(ConsumerRecord::key)
                .containsExactlyInAnyOrder(transferA.toString(), transferA.toString(), transferB.toString());
        assertThat(records).allSatisfy(record -> assertThat(new String(
                record.headers().lastHeader("content-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("application/cloudevents+json; charset=UTF-8"));
        assertThat(records).extracting(record -> JsonPath.<String>read(record.value(), "$.id"))
                .containsExactlyInAnyOrderElementsOf(eventIds.stream().map(UUID::toString).toList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE topic = ? AND published_at IS NOT NULL",
                Long.class, topic)).isEqualTo(3);
    }

    @Test
    void competingRelayInstancesKeepEachAggregatesEventsInOrder() throws Exception {
        String topic = createTopic();
        int aggregates = 40;
        int eventsPerAggregate = 5;
        List<UUID> ids = IntStream.range(0, aggregates).mapToObj(i -> UUID.randomUUID()).toList();
        // Interleaved, one transaction per event, like transfers committing over time.
        for (int seq = 0; seq < eventsPerAggregate; seq++) {
            for (UUID id : ids) {
                append(topic, id, seq);
            }
        }

        int relayInstances = 4;
        try (ExecutorService pool = Executors.newFixedThreadPool(relayInstances)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < relayInstances; i++) {
                runs.add(pool.submit(() -> {
                    int published = 0;
                    while (pendingCount(topic) > 0) {
                        published += relay.publishBatch();
                    }
                    return published;
                }));
            }
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get();
            }
            assertThat(total).isEqualTo(aggregates * eventsPerAggregate);
        }

        Map<String, List<Integer>> sequencesByKey = consume(topic, aggregates * eventsPerAggregate).stream()
                .collect(Collectors.groupingBy(ConsumerRecord::key, LinkedHashMap::new,
                        Collectors.mapping(record -> JsonPath.<Integer>read(record.value(), "$.data.seq"),
                                Collectors.toList())));
        assertThat(sequencesByKey).hasSize(aggregates);
        assertThat(sequencesByKey.values()).allSatisfy(sequence -> assertThat(sequence).containsExactly(0, 1, 2, 3, 4));
    }

    @Test
    void eventThatCannotBePublishedHoldsBackOnlyItsOwnAggregate() {
        String topic = createTopic();
        UUID poisoned = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        append("not a valid topic!", poisoned, 0);
        UUID heldBack = append(topic, poisoned, 1);
        append(topic, healthy, 0);

        drain();
        drain();

        assertThat(consume(topic, 1)).extracting(ConsumerRecord::key).containsExactly(healthy.toString());
        Map<String, Object> poison = jdbc.queryForMap(
                "SELECT attempts, last_error, published_at FROM outbox WHERE aggregate_id = ? ORDER BY id LIMIT 1",
                poisoned);
        assertThat((Integer) poison.get("attempts")).isGreaterThanOrEqualTo(2);
        assertThat((String) poison.get("last_error")).isNotBlank();
        assertThat(poison.get("published_at")).isNull();
        // Publishing the later event first would break the order consumers rely on.
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox WHERE event_id = ?", Boolean.class,
                heldBack)).isTrue();
    }

    private UUID append(String topic, UUID aggregateId, int seq) {
        CloudEvent<Map<String, Integer>> event = CloudEvent.of("/payledger/test", "com.payledger.test.happened", 1,
                aggregateId.toString(), "tester", Map.of("seq", seq));
        tx.executeWithoutResult(status -> outbox.append(topic, "test", aggregateId, event));
        return event.id();
    }

    private int drain() {
        int total = 0;
        int published;
        while ((published = relay.publishBatch()) > 0) {
            total += published;
        }
        return total;
    }

    private long pendingCount(String topic) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE topic = ? AND published_at IS NULL", Long.class,
                topic);
    }

    private String createTopic() {
        String topic = "relay-test-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return topic;
    }

    /** Reads the topic from the beginning until {@code expected} records arrived, plus a moment for extras. */
    private List<ConsumerRecord<String, String>> consume(String topic, int expected) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                return records.size() >= expected;
            });
            consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            return records;
        }
    }
}

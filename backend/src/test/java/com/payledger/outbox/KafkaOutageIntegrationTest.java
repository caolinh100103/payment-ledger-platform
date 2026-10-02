package com.payledger.outbox;

import com.jayway.jsonpath.JsonPath;
import com.payledger.support.ApiTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Interview demo 4: "turn Kafka off, make payments, turn it back on: no event is lost".
 *
 * <p>The broker is frozen with {@code docker pause}. To its clients that looks like a crashed or partitioned
 * broker: connections stay open but nothing answers. Payments must keep working, because they never touch Kafka,
 * and every event must reach Kafka once the broker is back.
 */
class KafkaOutageIntegrationTest extends ApiTestSupport {

    private static final String TOPIC = "payledger.transfers";
    private static final int TRANSFERS = 20;

    @Autowired
    KafkaContainer kafka;

    @Test
    void paymentsKeepWorkingWhileKafkaIsDownAndNoEventIsLost() {
        String alice = fundedAccount("VND", 10_000_000);
        String bob = openAccount("VND");
        List<String> transferIds = new ArrayList<>();

        pauseKafka();
        try {
            for (int i = 0; i < TRANSFERS; i++) {
                long start = System.nanoTime();
                MvcTestResult result = transfer(alice, bob, 10_000, "VND");
                Duration took = Duration.ofNanos(System.nanoTime() - start);

                assertThat(result).hasStatus(HttpStatus.CREATED);
                // The payment path never waits for the broker (max.block.ms alone is 5 s).
                assertThat(took).isLessThan(Duration.ofSeconds(2));
                transferIds.add(jsonPath(result, "$.id"));
            }
            assertThat(balanceOf(bob)).isEqualTo(TRANSFERS * 10_000L);

            // The relay keeps trying and failing; the events wait in the outbox. (Once a send times out the relay
            // stops the batch, so only the oldest pending row counts the attempt.)
            await().atMost(Duration.ofSeconds(30)).until(() -> failedAttemptsOnPendingEvents() > 0);
            assertThat(pendingEvents(transferIds)).isEqualTo(2L * TRANSFERS);
        } finally {
            unpauseKafka();
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> pendingEvents(transferIds) == 0);

        Map<String, List<String>> typesByTransfer = consumeEventsOf(Set.copyOf(transferIds));
        assertThat(typesByTransfer).hasSize(TRANSFERS);
        assertThat(typesByTransfer.values()).allSatisfy(types -> assertThat(types)
                .containsExactly("com.payledger.transfer.created", "com.payledger.transfer.completed"));
    }

    private void pauseKafka() {
        DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private void unpauseKafka() {
        DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    private long pendingEvents(List<String> transferIds) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE published_at IS NULL AND aggregate_id::text = ANY (?)
                """, Long.class, (Object) transferIds.toArray(String[]::new));
    }

    private long failedAttemptsOnPendingEvents() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(attempts), 0) FROM outbox WHERE published_at IS NULL",
                Long.class);
    }

    /** Event types per transfer, in the order a consumer receives them. */
    private Map<String, List<String>> consumeEventsOf(Set<String> transferIds) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(TOPIC, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<String, List<String>> types = new HashMap<>();
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                return received.stream().filter(r -> transferIds.contains(r.key())).count() >= 2L * transferIds.size();
            });
            received.stream().filter(r -> transferIds.contains(r.key())).forEach(r -> types
                    .computeIfAbsent(r.key(), k -> new ArrayList<>())
                    .add(JsonPath.read(r.value(), "$.type")));
            return types.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }
}

package com.payledger.common.observability;

import com.jayway.jsonpath.JsonPath;
import com.payledger.outbox.CloudEvent;
import com.payledger.outbox.EventTopics;
import com.payledger.outbox.Outbox;
import com.payledger.support.ApiTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * One trace id from the HTTP request, through the outbox (where the thread changes and the relay may run much later),
 * to the Kafka record the consumers continue from.
 */
class TraceContextIntegrationTest extends ApiTestSupport {

    private static final String COMPLETED = "com.payledger.transfer.completed";

    @Autowired
    KafkaContainer kafka;

    @Autowired
    Outbox outbox;

    @Autowired
    TransactionTemplate tx;

    @Test
    void continuesTheCallersTraceThroughTheOutboxToTheKafkaRecord() {
        String alice = fundedAccount("VND", 1_000_000);
        String bob = openAccount("VND");
        String traceId = randomHex(16);
        String callerSpanId = randomHex(8);

        MvcTestResult result = mvc.post().uri("/api/v1/transfers")
                .with(asOwnerOf(alice))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("traceparent", "00-" + traceId + "-" + callerSpanId + "-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(alice, bob, 250_000, "VND"))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED).headers().hasValue("X-Trace-Id", traceId);
        String transferId = jsonPath(result, "$.id");

        // The event carries the context of the server's span for the request: same trace, a span of our own.
        String stored = storedTraceparent(transferId, COMPLETED);
        assertThat(traceIdOf(stored)).isEqualTo(traceId);
        assertThat(spanIdOf(stored)).isNotEqualTo(callerSpanId);

        // The record carries the relay's publish span: still the same trace, a child of the stored context.
        ConsumerRecord<String, String> record = awaitRecord(transferId, COMPLETED);
        String sent = header(record, "traceparent");
        assertThat(traceIdOf(sent)).isEqualTo(traceId);
        assertThat(spanIdOf(sent)).isNotIn(callerSpanId, spanIdOf(stored));
        assertThat(JsonPath.<String>read(record.value(), "$.traceparent")).isEqualTo(stored);
    }

    @Test
    void startsATraceWhenTheCallerSentNoneAndReturnsItsId() {
        String alice = fundedAccount("VND", 1_000_000);

        MvcTestResult result = transfer(alice, openAccount("VND"), 1_000, "VND");

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(traceIdOf(storedTraceparent(jsonPath(result, "$.id"), COMPLETED))).isEqualTo(traceId);
    }

    @Test
    void eventWrittenOutsideAnyTraceHasNoTraceContext() {
        UUID aggregateId = UUID.randomUUID();
        CloudEvent<Map<String, String>> event = CloudEvent.of("/payledger/test", "com.payledger.test.happened", 1,
                aggregateId.toString(), "system", Map.of());

        // Rolled back, so the relay never tries to publish to a topic that does not exist.
        String payload = tx.execute(status -> {
            status.setRollbackOnly();
            outbox.append("payledger.test", "test", aggregateId, event);
            return jdbc.queryForObject("SELECT payload::text FROM outbox WHERE event_id = ?", String.class, event.id());
        });

        assertThat(payload).contains("\"actor\":\"system\"").doesNotContain("traceparent");
    }

    @Test
    void echoesTheCallersInteractionIdAndReplacesAnythingThatIsNotAUuid() {
        String interactionId = UUID.randomUUID().toString();

        assertThat(mvc.get().uri("/api/v1/users/me").with(asCustomer(newCustomer()))
                .header("x-fapi-interaction-id", interactionId))
                .headers().hasValue("x-fapi-interaction-id", interactionId);

        MvcTestResult forged = mvc.get().uri("/api/v1/users/me").with(asCustomer(newCustomer()))
                .header("x-fapi-interaction-id", "<script>alert(1)</script>").exchange();
        assertThat(forged.getResponse().getHeader("x-fapi-interaction-id"))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void rejectedRequestsCarryTheIdsToo() {
        MvcTestResult unauthenticated = mvc.get().uri("/api/v1/accounts").exchange();

        assertThat(unauthenticated).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(unauthenticated.getResponse().getHeader("X-Trace-Id")).matches("[0-9a-f]{32}");
        assertThat(unauthenticated.getResponse().getHeader("x-fapi-interaction-id")).isNotBlank();
    }

    private String storedTraceparent(String transferId, String type) {
        return jdbc.queryForObject("""
                SELECT payload ->> 'traceparent' FROM outbox WHERE aggregate_id = ?::uuid AND event_type = ?
                """, String.class, transferId, type);
    }

    private ConsumerRecord<String, String> awaitRecord(String key, String type) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(EventTopics.TRANSFERS).stream()
                    .map(info -> new TopicPartition(EventTopics.TRANSFERS, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key()) && type.equals(JsonPath.read(record.value(), "$.type"))) {
                        found.set(record);
                    }
                }
                return found.get() != null;
            });
            return found.get();
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** {@code 00-<trace id>-<span id>-<flags>}; flags 03 = sampled, and a random trace id (Trace Context Level 2). */
    private static String traceIdOf(String traceparent) {
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        return traceparent.split("-")[1];
    }

    private static String spanIdOf(String traceparent) {
        return traceparent.split("-")[2];
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}

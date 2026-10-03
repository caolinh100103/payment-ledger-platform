package com.payledger.notification;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.payledger.notification.TestcontainersConfiguration.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The notification is sent within the trace of the request that moved the money, found by the same trace id. */
@ExtendWith(OutputCaptureExtension.class)
class TraceContextIntegrationTest extends ConsumerTestSupport {

    @Test
    void sendsTheBalanceChangeMessageWithinTheTraceOfTheCoreRequest(CapturedOutput output) throws Exception {
        UUID eventId = UUID.randomUUID();
        String traceId = randomHex(16);

        // As the core's outbox relay publishes it: the trace context in the record's traceparent header.
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "transfer-traced",
                TestEvents.completedTransfer(eventId, "Rent"));
        record.headers().add("traceparent",
                ("00-" + traceId + "-" + randomHex(8) + "-01").getBytes(StandardCharsets.UTF_8));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(record).get();
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> store.findByEvent(eventId).size() == 2);
        // Both SMS log lines (payer and payee) carry the core's trace id, plain text or JSON alike.
        assertThat(output.getAll().lines().filter(line -> line.contains(traceId) && line.contains("SMS to")))
                .hasSize(2);
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}

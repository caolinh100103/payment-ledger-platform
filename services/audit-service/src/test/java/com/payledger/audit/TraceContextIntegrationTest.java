package com.payledger.audit;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static com.payledger.audit.AuditTestEvents.cloudEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

/** An event is recorded within the trace of the request that caused it, as the core's relay passes it on. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TraceContextIntegrationTest {

    private static final String TOPIC = "payledger.transfers";

    @MockitoSpyBean
    AuditLog auditLog;

    @Autowired
    Tracer tracer;

    @Autowired
    KafkaContainer kafkaContainer;

    @Test
    void recordsTheEventWithinTheTraceOfTheCoreRequest() throws Exception {
        UUID eventId = UUID.randomUUID();
        String transfer = UUID.randomUUID().toString();
        String traceId = randomHex(16);
        AtomicReference<String> traceIdWhileRecording = new AtomicReference<>();
        doAnswer(invocation -> {
            Span current = tracer.currentSpan();
            traceIdWhileRecording.set(current == null ? "none" : current.context().traceId());
            return invocation.callRealMethod();
        }).when(auditLog).append(argThat(event -> event.eventId().equals(eventId)));

        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, transfer,
                cloudEvent(eventId, "com.payledger.transfer.completed", transfer));
        record.headers().add("traceparent",
                ("00-" + traceId + "-" + randomHex(8) + "-01").getBytes(StandardCharsets.UTF_8));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(record).get();
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> auditLog.findByResource(transfer, 10).size() == 1);
        assertThat(traceIdWhileRecording.get()).isEqualTo(traceId);
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}

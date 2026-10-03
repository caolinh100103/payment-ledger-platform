package com.payledger.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * Records every event published on the domain topics: {@code payledger.transfers}, {@code payledger.accounts} and
 * {@code payledger.security}, into one hash chain. Kafka delivers at least once, so a redelivered event is
 * recognised by its id and not recorded twice. The offset is committed only after the row is committed.
 *
 * <p>Failures are retried without blocking the topic (ADR 0008): after 2 s, 6 s, 18 s and 54 s on
 * {@code <topic>-audit-retry-0..3}, typically riding out a database failover. An event that still fails, or can
 * never be recorded (not a CloudEvent), is parked on {@code <topic>-audit-dlt}. A gap in the audit trail is a
 * compliance incident, so that is logged as an error and counted in
 * {@code payledger_events_dead_lettered_total{topic}}, which pages the on-call engineer.
 *
 * <p>{@code payledger_events_consumed_total{outcome}} counts events recorded ({@code processed}) and redeliveries
 * recognised and skipped ({@code duplicate}): at-least-once delivery, made visible.
 */
@Component
class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditLog auditLog;
    private final JsonMapper json;
    private final MeterRegistry meters;

    AuditEventListener(AuditLog auditLog, JsonMapper json, MeterRegistry meters,
                       @Value("${payledger.audit.topics}") String[] topics) {
        this.auditLog = auditLog;
        this.json = json;
        this.meters = meters;
        // From zero: a counter born at 1 shows no increase, and the AUDIT GAP alert would miss a single parked event.
        meters.counter("payledger.events.consumed", "outcome", "processed");
        meters.counter("payledger.events.consumed", "outcome", "duplicate");
        for (String topic : topics) {
            meters.counter("payledger.events.dead.lettered", "topic", topic.trim());
        }
    }

    @RetryableTopic(
            attempts = "${payledger.audit.retry.attempts:5}",
            backOff = @BackOff(delayString = "${payledger.audit.retry.initial-delay-ms:2000}",
                    multiplierString = "${payledger.audit.retry.multiplier:3.0}",
                    maxDelayString = "${payledger.audit.retry.max-delay-ms:60000}"),
            // Named after this service: the notification service consumes the same topic with its own retries.
            retryTopicSuffix = "-audit-retry",
            dltTopicSuffix = "-audit-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = MalformedEventException.class,
            traversingCauses = "true")
    // idIsGroup = false: the consumer group is spring.kafka.consumer.group-id, not the listener id.
    @KafkaListener(id = "audit", idIsGroup = false, topics = "#{'${payledger.audit.topics}'.split(',')}")
    void onEvent(ConsumerRecord<String, String> record) {
        AuditableEvent event = AuditableEvent.parse(record.value(), json);
        if (auditLog.append(event)) {
            log.debug("Recorded {} {} for {}", event.action(), event.eventId(), event.resourceId());
            meters.counter("payledger.events.consumed", "outcome", "processed").increment();
        } else {
            log.info("Skipped redelivered event {} ({}-{}@{})", event.eventId(), record.topic(), record.partition(),
                    record.offset());
            meters.counter("payledger.events.consumed", "outcome", "duplicate").increment();
        }
    }

    @DltHandler
    void onDeadLetter(ConsumerRecord<String, String> record) {
        String originalTopic = header(record, KafkaHeaders.ORIGINAL_TOPIC);
        log.error("AUDIT GAP: event parked on {} (key {}, offset {}), originally from {}: {}", record.topic(),
                record.key(), record.offset(), originalTopic, header(record, KafkaHeaders.EXCEPTION_MESSAGE));
        meters.counter("payledger.events.dead.lettered", "topic", originalTopic == null ? record.topic() : originalTopic)
                .increment();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}

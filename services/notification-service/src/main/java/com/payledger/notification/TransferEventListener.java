package com.payledger.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Consumes transfer events with non-blocking retries (ADR 0008).
 *
 * <p>A failed event is moved to {@code payledger.transfers-notification-retry-0}, {@code -1}, {@code -2} and
 * retried after 1 s, 2 s and 4 s, while the main topic keeps flowing. If the last attempt fails, or the event can
 * never succeed (malformed, unsupported schema version), it is parked on {@code payledger.transfers-notification-dlt}
 * for a human to inspect and replay.
 *
 * <p>The suffixes name this service on purpose: the audit service consumes the same topic, and with the default
 * suffixes both services would share retry topics and consume each other's retries.
 */
@Component
class TransferEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventListener.class);

    private final TransferNotifier notifier;
    private final JsonMapper json;
    private final MeterRegistry meters;

    TransferEventListener(TransferNotifier notifier, JsonMapper json, MeterRegistry meters) {
        this.notifier = notifier;
        this.json = json;
        this.meters = meters;
    }

    @RetryableTopic(
            attempts = "${payledger.notification.retry.attempts:4}",
            backOff = @BackOff(delayString = "${payledger.notification.retry.initial-delay-ms:1000}",
                    multiplierString = "${payledger.notification.retry.multiplier:2.0}",
                    maxDelayString = "${payledger.notification.retry.max-delay-ms:30000}"),
            retryTopicSuffix = "-notification-retry",
            dltTopicSuffix = "-notification-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            // Retrying cannot fix these: straight to the dead-letter topic.
            exclude = {MalformedEventException.class, UnsupportedEventException.class},
            traversingCauses = "true")
    // idIsGroup = false: the consumer group is spring.kafka.consumer.group-id, not the listener id.
    @KafkaListener(id = "notifications", idIsGroup = false, topics = "${payledger.notification.topic}")
    void onEvent(ConsumerRecord<String, String> record) {
        notifier.handle(TransferEvent.parse(record.value(), json));
    }

    /**
     * Parked events need a person: a customer did not get a balance-change message. Counted in
     * {@code payledger_events_dead_lettered_total{topic}}, which the alert fires on.
     */
    @DltHandler
    void onDeadLetter(ConsumerRecord<String, String> record) {
        String originalTopic = header(record, KafkaHeaders.ORIGINAL_TOPIC);
        log.error("Event parked on {} (key {}, offset {}), originally from {}: {}", record.topic(), record.key(),
                record.offset(), originalTopic, header(record, KafkaHeaders.EXCEPTION_MESSAGE));
        meters.counter("payledger.events.dead.lettered", "topic", originalTopic == null ? record.topic() : originalTopic)
                .increment();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}

package com.payledger.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes outbox rows to Kafka (ADR 0007). Safe to run on every instance of the service at once:
 *
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED}: instances take disjoint batches instead of waiting for each other.</li>
 *   <li>Only the <em>oldest pending event of each aggregate</em> is eligible. While one instance is publishing
 *       event 1 of a transfer, no other instance can pick event 2, so a transfer's events always reach Kafka
 *       in order. Kafka then keeps that order because they share a key, and so a partition.</li>
 *   <li>A row is marked published only after Kafka acknowledged it ({@code acks=all}), in the transaction
 *       that holds its lock. A crash between the acknowledgement and the commit republishes the event:
 *       delivery is at-least-once and consumers deduplicate on the event id.</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String CONTENT_TYPE_HEADER = "content-type";
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager txManager,
                       @Value("${payledger.outbox.relay.batch-size:100}") int batchSize,
                       @Value("${payledger.outbox.relay.send-timeout:15s}") Duration sendTimeout) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Publishes one batch of pending events.
     *
     * @return how many events were published; 0 when there is nothing to do or Kafka is unavailable
     */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            List<PendingEvent> batch = lockNextBatch();
            if (batch.isEmpty()) {
                return 0;
            }
            List<Long> publishedIds = publish(batch);
            jdbc.batchUpdate("UPDATE outbox SET published_at = now() WHERE id = ?",
                    publishedIds.stream().map(id -> new Object[]{id}).toList());
            return publishedIds.size();
        });
        return published == null ? 0 : published;
    }

    private List<PendingEvent> lockNextBatch() {
        return jdbc.query("""
                SELECT id, aggregate_id, topic, payload::text AS payload
                FROM outbox o
                WHERE published_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM outbox older
                                  WHERE older.aggregate_id = o.aggregate_id
                                    AND older.published_at IS NULL
                                    AND older.id < o.id)
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new PendingEvent(rs.getLong("id"), rs.getObject("aggregate_id", UUID.class),
                rs.getString("topic"), rs.getString("payload")), batchSize);
    }

    /** Sends the whole batch, then waits for the acknowledgements. Returns the ids Kafka acknowledged. */
    private List<Long> publish(List<PendingEvent> batch) {
        List<CompletableFuture<?>> sends = new ArrayList<>(batch.size());
        for (PendingEvent event : batch) {
            CompletableFuture<?> send = send(event);
            sends.add(send);
            if (send.isCompletedExceptionally() && isTimeout(send.exceptionNow())) {
                // No metadata within max.block.ms: the broker is unreachable, and every further send would block
                // just as long. Other errors (e.g. an invalid topic) only concern that one event, so go on.
                break;
            }
        }

        long deadline = System.nanoTime() + sendTimeout.toNanos();
        List<Long> published = new ArrayList<>(sends.size());
        String lastError = null;
        for (int i = 0; i < sends.size(); i++) {
            PendingEvent event = batch.get(i);
            try {
                sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                published.add(event.id());
            } catch (ExecutionException | TimeoutException e) {
                lastError = describe(e);
                recordFailure(event, lastError);
            } catch (InterruptedException e) {
                // Shutting down: what was acknowledged so far is still marked; the rest is retried later.
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (lastError != null) {
            log.warn("Published {} of {} outbox events, the rest will be retried: {}", published.size(), batch.size(),
                    lastError);
        }
        return published;
    }

    private CompletableFuture<?> send(PendingEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.aggregateId().toString(),
                event.payload());
        record.headers().add(new RecordHeader(CONTENT_TYPE_HEADER,
                CloudEvent.STRUCTURED_CONTENT_TYPE.getBytes(StandardCharsets.UTF_8)));
        try {
            return kafka.send(record);
        } catch (RuntimeException e) {
            // e.g. metadata for the topic could not be fetched within max.block.ms
            return CompletableFuture.failedFuture(e);
        }
    }

    private void recordFailure(PendingEvent event, String error) {
        jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error, event.id());
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof org.apache.kafka.common.errors.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Exception e) {
        // The root cause (e.g. InvalidTopicException) says more than Spring's "Send failed" wrapper.
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private record PendingEvent(long id, UUID aggregateId, String topic, String payload) {
    }
}

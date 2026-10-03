package com.payledger.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * The write side of the transactional outbox (ADR 0003). Publishing to Kafka directly from a business
 * transaction is a dual write: a crash between the database commit and the send loses the event, and a send
 * followed by a rollback announces something that never happened. Instead, the event is inserted into the
 * {@code outbox} table by the transaction that makes the change, and {@link OutboxRelay} publishes it afterwards.
 */
@Repository
public class Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final OutboxTracing tracing;

    public Outbox(JdbcTemplate jdbc, JsonMapper jsonMapper, OutboxTracing tracing) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.tracing = tracing;
    }

    /**
     * Records {@code event} for publication to {@code topic}, keyed by {@code aggregateId}.
     *
     * <p>{@code MANDATORY}: an event written in a transaction of its own would be exactly the dual write this
     * class exists to prevent, so calling it without a transaction is a programming error.
     *
     * <p>The event is stamped with the current trace context (see {@link OutboxTracing}), so its publication and
     * consumption join the trace of the request that caused it.
     *
     * <p>{@code created_at} is the moment of this insert, near the end of the transaction, not the column default
     * {@code now()}, which is the start of the transaction: the delivery delay and the backlog age must not include
     * the time a transfer waited for its account locks.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String aggregateType, UUID aggregateId, CloudEvent<?> event) {
        CloudEvent<?> traced = tracing.stamp(event);
        jdbc.update("""
                INSERT INTO outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?::json, clock_timestamp())
                """, traced.id(), aggregateType, aggregateId, traced.type(), topic, jsonMapper.writeValueAsString(traced));
    }
}

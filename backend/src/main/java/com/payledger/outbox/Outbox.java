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
 * {@code outbox} table by the transaction that makes the change, and a relay publishes it afterwards.
 */
@Repository
public class Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public Outbox(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Records {@code event} for publication to {@code topic}, keyed by {@code aggregateId}.
     *
     * <p>{@code MANDATORY}: an event written in a transaction of its own would be exactly the dual write this
     * class exists to prevent, so calling it without a transaction is a programming error.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String aggregateType, UUID aggregateId, CloudEvent<?> event) {
        jdbc.update("""
                INSERT INTO outbox (event_id, aggregate_type, aggregate_id, event_type, topic, payload)
                VALUES (?, ?, ?, ?, ?, ?::json)
                """, event.id(), aggregateType, aggregateId, event.type(), topic, jsonMapper.writeValueAsString(event));
    }
}

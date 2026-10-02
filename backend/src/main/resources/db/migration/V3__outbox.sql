-- Phase 3: transactional outbox (ADR 0003, ADR 0007).
--
-- Domain events are inserted here in the same transaction as the state change they describe, so an event
-- exists if and only if its change committed. A relay publishes the rows to Kafka afterwards.

CREATE TABLE outbox (
    -- Publication order. All events of one aggregate are written by transactions that hold that aggregate's
    -- row lock, so for a given aggregate_id, id order is also commit order.
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The CloudEvents id. Delivery is at-least-once, so consumers deduplicate on it.
    event_id        UUID          NOT NULL,
    aggregate_type  VARCHAR(32)   NOT NULL,
    -- Kafka message key: every event of one aggregate lands on the same partition, in order.
    aggregate_id    UUID          NOT NULL,
    event_type      VARCHAR(128)  NOT NULL,
    -- 249: Kafka's maximum topic name length.
    topic           VARCHAR(249)  NOT NULL,
    -- The complete CloudEvent as it goes on the wire. JSON (not JSONB) keeps the text byte for byte,
    -- while still allowing ad-hoc queries such as payload -> 'data' ->> 'amount'.
    payload         JSON          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    -- Failed publish attempts, for operators: a row stuck with many attempts needs a look.
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

-- The relay scans pending rows in id order, and checks that no older pending row of the same aggregate exists.
CREATE INDEX idx_outbox_pending           ON outbox (id) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_pending_aggregate ON outbox (aggregate_id, id) WHERE published_at IS NULL;
-- Published rows are kept for a while (debugging, replay) and then deleted in batches.
CREATE INDEX idx_outbox_published_at      ON outbox (published_at) WHERE published_at IS NOT NULL;

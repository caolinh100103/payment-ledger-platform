-- Idempotent consumer (ADR 0008). Kafka delivers at least once: after a crash, a rebalance or a relay retry the
-- same event can arrive again. The event id is recorded in the same transaction as the notifications it caused,
-- so either both are committed or neither is, and a redelivered event finds its id and is skipped.
CREATE TABLE processed_events (
    consumer      VARCHAR(64)  NOT NULL,
    event_id      UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (consumer, event_id)
);

-- Rows only need to outlive the topic's retention (an event can only be redelivered while Kafka still has it).
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

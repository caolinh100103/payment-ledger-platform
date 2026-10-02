-- Messages sent to customers, kept as a history (what was sent, to whom, because of which event).
CREATE TABLE notifications (
    id            UUID          PRIMARY KEY,
    -- The CloudEvents id of the event that caused this message.
    event_id      UUID          NOT NULL,
    recipient_id  VARCHAR(64)   NOT NULL,
    channel       VARCHAR(16)   NOT NULL,
    template      VARCHAR(64)   NOT NULL,
    message       VARCHAR(1000) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,

    CONSTRAINT chk_notifications_channel CHECK (channel IN ('SMS', 'EMAIL', 'PUSH'))
);

CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, created_at);
CREATE INDEX idx_notifications_event ON notifications (event_id);

-- Tamper-evident audit log (ADR 0009). Three independent layers:
--
--   1. Privileges: the application connects as audit_app, which may only SELECT and INSERT.
--      Migrations run as the schema owner.
--   2. Triggers: UPDATE, DELETE and TRUNCATE are rejected for every role, including the owner.
--   3. Hash chain: each row stores the SHA-256 of its content plus the previous row's hash. Someone who
--      bypasses 1 and 2 (a superuser disabling triggers) can still change rows, but not without breaking
--      the chain, which GET /api/v1/audit-events/verification detects.

CREATE TABLE audit_events (
    -- Position in the chain: 1, 2, 3, ... without gaps, so a deleted row shows up as a gap.
    seq           BIGINT        PRIMARY KEY,
    -- The CloudEvents id. Events arrive at least once; a redelivery is recognised here and not recorded twice.
    event_id      UUID          NOT NULL,
    -- Who did what to which resource, and when.
    actor         VARCHAR(64)   NOT NULL,
    action        VARCHAR(128)  NOT NULL,
    resource_id   VARCHAR(255),
    occurred_at   TIMESTAMPTZ   NOT NULL,
    source        VARCHAR(255)  NOT NULL,
    -- The complete event exactly as received (TEXT, not JSONB, so the hashed bytes are what is stored).
    payload       TEXT          NOT NULL,
    recorded_at   TIMESTAMPTZ   NOT NULL,
    prev_hash     CHAR(64)      NOT NULL,
    hash          CHAR(64)      NOT NULL,

    CONSTRAINT uq_audit_events_event_id UNIQUE (event_id),
    CONSTRAINT uq_audit_events_hash UNIQUE (hash),
    CONSTRAINT chk_audit_events_seq_positive CHECK (seq > 0)
);

CREATE INDEX idx_audit_events_resource ON audit_events (resource_id, seq);

-- Layer 2: no role, the owner included, may change or remove a recorded event.
CREATE FUNCTION forbid_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_mutation();

-- Layer 1: least privilege for the runtime role (created by the infrastructure, see infra/postgres).
REVOKE ALL ON audit_events FROM PUBLIC;
GRANT SELECT, INSERT ON audit_events TO audit_app;

-- "What did this user do?": PCI DSS 10.2.1 asks for the actions of each individual to be traceable, and
-- GET /api/v1/audit-events/latest?actor= pages through them, newest first.
CREATE INDEX idx_audit_events_actor ON audit_events (actor, seq);

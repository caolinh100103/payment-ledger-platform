-- Runs once, when the audit database volume is first created.
-- The application connects as audit_app, which V1__audit_events.sql only grants SELECT and INSERT;
-- migrations run as the owner (POSTGRES_USER, audit_owner). Development passwords only.
CREATE ROLE audit_app LOGIN PASSWORD 'audit_app';

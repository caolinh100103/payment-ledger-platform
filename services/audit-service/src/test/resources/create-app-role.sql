-- Same role the infrastructure creates (infra/postgres/audit-init.sql): the application connects as it.
CREATE ROLE audit_app LOGIN PASSWORD 'audit_app';

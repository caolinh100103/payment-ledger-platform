-- Phase 4: people who sign in (ADR 0010). Machine clients authenticate with API keys instead.
--
-- A user's id is the subject (sub) of their access tokens, and the owner_id of the accounts they own.

CREATE TABLE users (
    id                     UUID          PRIMARY KEY,
    -- Stored lower-case, so "Alice" and "alice" cannot be two different users.
    username               VARCHAR(64)   NOT NULL,
    -- A PHC string with an algorithm prefix, e.g. {argon2}$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>. The
    -- prefix lets the algorithm or its cost be raised later: each user is re-hashed at their next sign-in.
    password_hash          VARCHAR(255)  NOT NULL,
    role                   VARCHAR(16)   NOT NULL,
    -- Consecutive failed sign-ins. Reaching the limit sets locked_until; a successful sign-in resets it.
    failed_login_attempts  INT           NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    last_login_at          TIMESTAMPTZ,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT chk_users_username_lower_case CHECK (username = lower(username)),
    CONSTRAINT chk_users_role CHECK (role IN ('CUSTOMER', 'OPERATOR', 'AUDITOR', 'ADMIN')),
    CONSTRAINT chk_users_failed_login_attempts CHECK (failed_login_attempts >= 0)
);

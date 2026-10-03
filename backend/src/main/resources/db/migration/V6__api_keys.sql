-- Phase 4: credentials of machine clients, such as the partner bank integration that reports top-ups (ADR 0011).

CREATE TABLE api_keys (
    id            UUID          PRIMARY KEY,
    -- What the key is for, e.g. "Vietcombank top-up webhook".
    name          VARCHAR(100)  NOT NULL,
    -- The first characters of the key, shown in listings so a key can be recognised without being revealed.
    prefix        VARCHAR(16)   NOT NULL,
    -- SHA-256 of the whole key; the key itself is shown once, at creation, and never stored. It has about 190 random
    -- bits, so a fast hash is enough, and authenticating a request is a single index lookup.
    key_hash      CHAR(64)      NOT NULL,
    -- Space-separated, like an OAuth 2.0 scope (RFC 6749 §3.3), e.g. 'deposits:write'.
    scopes        VARCHAR(255)  NOT NULL,
    created_by    VARCHAR(64)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    expires_at    TIMESTAMPTZ,
    -- Revoked keys are kept, so the audit trail can still say whose key an old request used.
    revoked_at    TIMESTAMPTZ,
    -- Updated at most once a minute, so a busy client does not turn every request into a write.
    last_used_at  TIMESTAMPTZ,

    CONSTRAINT uq_api_keys_key_hash UNIQUE (key_hash),
    CONSTRAINT chk_api_keys_scopes CHECK (scopes <> '')
);

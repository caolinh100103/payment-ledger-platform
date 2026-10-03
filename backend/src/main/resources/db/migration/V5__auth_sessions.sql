-- Phase 4: sign-in sessions and their refresh tokens (ADR 0010).
--
-- A session starts at sign-in. Each refresh exchanges the current refresh token for a new one (rotation), so a
-- session is a chain of tokens of which only the newest is usable. If an older token is presented again, it was
-- copied: the whole session is revoked (RFC 9700 §4.14.2, refresh token reuse detection).

CREATE TABLE auth_sessions (
    id             UUID          PRIMARY KEY,
    user_id        UUID          NOT NULL REFERENCES users (id),
    created_at     TIMESTAMPTZ   NOT NULL,
    -- Absolute lifetime: after this the user signs in again, however active they are.
    expires_at     TIMESTAMPTZ   NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoke_reason  VARCHAR(32),

    CONSTRAINT chk_auth_sessions_revoked CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    CONSTRAINT chk_auth_sessions_revoke_reason CHECK (revoke_reason IN ('LOGOUT', 'REFRESH_TOKEN_REUSE'))
);

CREATE INDEX idx_auth_sessions_user       ON auth_sessions (user_id);
CREATE INDEX idx_auth_sessions_expires_at ON auth_sessions (expires_at);

CREATE TABLE refresh_tokens (
    -- SHA-256 of the token, never the token itself, so a copy of this table cannot be used to refresh. The token
    -- is 256 random bits: unlike a password it cannot be guessed, so a fast unsalted hash is enough.
    token_hash   CHAR(64)      PRIMARY KEY,
    session_id   UUID          NOT NULL REFERENCES auth_sessions (id) ON DELETE CASCADE,
    issued_at    TIMESTAMPTZ   NOT NULL,
    -- Idle timeout: a session that is not refreshed within this time ends.
    expires_at   TIMESTAMPTZ   NOT NULL,
    -- Set when the token is exchanged for the next one. Kept until the session ends, to recognise reuse.
    used_at      TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_session ON refresh_tokens (session_id);

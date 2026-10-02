-- Money is stored as BIGINT in the currency's minor unit (e.g. cents for USD, dong for VND).
-- Never use floating point for money.
CREATE TABLE accounts (
    id          UUID         PRIMARY KEY,
    owner_id    VARCHAR(64)  NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    balance     BIGINT       NOT NULL DEFAULT 0,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT chk_accounts_status   CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_accounts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- Last line of defence against overdraft: even a buggy code path cannot persist a negative balance.
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0)
);

CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);

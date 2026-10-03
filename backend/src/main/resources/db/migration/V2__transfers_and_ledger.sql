-- Phase 2: double-entry ledger.
--
-- Sign convention: every account's balance = credits - debits. Customer wallets are what the platform
-- owes its customers, so they must never go negative. SYSTEM accounts are the platform's side of each
-- movement (e.g. the funding account mirrors money held at the partner bank) and may go negative.
-- Because every transfer posts one DEBIT and one CREDIT of equal amount, SUM(balance) over all accounts
-- of a currency is always 0.

-- ---------------------------------------------------------------------------------------------------
-- Account types
-- ---------------------------------------------------------------------------------------------------
ALTER TABLE accounts ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE accounts ALTER COLUMN type DROP DEFAULT;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_type CHECK (type IN ('CUSTOMER', 'SYSTEM'));

ALTER TABLE accounts DROP CONSTRAINT chk_accounts_balance_non_negative;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_balance_non_negative
    CHECK (type = 'SYSTEM' OR balance >= 0);

-- One funding account per currency: deposits are debited from it.
CREATE UNIQUE INDEX uq_accounts_system_currency ON accounts (currency) WHERE type = 'SYSTEM';

INSERT INTO accounts (id, owner_id, currency, status, balance, version, created_at, updated_at, type)
SELECT gen_random_uuid(), 'system', c.currency, 'ACTIVE', 0, 0, now(), now(), 'SYSTEM'
FROM (VALUES ('VND'), ('USD'), ('EUR')) AS c(currency);

-- ---------------------------------------------------------------------------------------------------
-- Transfers: one row per money movement request, including rejected ones (audit trail).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE transfers (
    id                      UUID          PRIMARY KEY,
    type                    VARCHAR(16)   NOT NULL,
    status                  VARCHAR(16)   NOT NULL,
    source_account_id       UUID          NOT NULL REFERENCES accounts (id),
    destination_account_id  UUID          NOT NULL REFERENCES accounts (id),
    amount                  BIGINT        NOT NULL,
    currency                VARCHAR(3)    NOT NULL,
    -- 140 chars: the unstructured remittance information limit of ISO 20022 / SWIFT MT103.
    description             VARCHAR(140),
    failure_code            VARCHAR(64),
    failure_reason          VARCHAR(255),
    reversal_of             UUID          REFERENCES transfers (id),
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,

    CONSTRAINT chk_transfers_type     CHECK (type IN ('DEPOSIT', 'TRANSFER', 'REVERSAL')),
    CONSTRAINT chk_transfers_status   CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED')),
    CONSTRAINT chk_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transfers_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_transfers_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT chk_transfers_failure  CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT chk_transfers_reversal CHECK ((type = 'REVERSAL') = (reversal_of IS NOT NULL))
);

CREATE INDEX idx_transfers_source_account      ON transfers (source_account_id, created_at);
CREATE INDEX idx_transfers_destination_account ON transfers (destination_account_id, created_at);
-- A transfer can be reversed successfully at most once (failed reversal attempts are kept as history).
CREATE UNIQUE INDEX uq_transfers_completed_reversal ON transfers (reversal_of) WHERE status = 'COMPLETED';

-- ---------------------------------------------------------------------------------------------------
-- Ledger entries: the source of truth for balances. Append-only.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    -- A sequence gives every posting a total order, so an account's statement is ordered by id.
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id    UUID         NOT NULL REFERENCES transfers (id),
    account_id     UUID         NOT NULL REFERENCES accounts (id),
    direction      VARCHAR(6)   NOT NULL,
    amount         BIGINT       NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    -- Running balance after this entry, like the balance column on a bank statement.
    balance_after  BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_account  ON ledger_entries (account_id, id);
CREATE INDEX idx_ledger_entries_transfer ON ledger_entries (transfer_id);

-- Posted entries are never changed; corrections are made with compensating entries.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_mutation();

-- Double-entry invariant, checked at COMMIT: the entries of a transfer must balance.
-- Deferred so the debit and credit can be inserted as separate statements in the same transaction.
CREATE FUNCTION check_transfer_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    total_debit  BIGINT;
    total_credit BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
    INTO total_debit, total_credit
    FROM ledger_entries
    WHERE transfer_id = NEW.transfer_id;

    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'transfer % is unbalanced: debit % <> credit %',
            NEW.transfer_id, total_debit, total_credit
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transfer_balanced();

-- ---------------------------------------------------------------------------------------------------
-- Idempotency keys (IETF draft "The Idempotency-Key HTTP Header Field", Stripe-style semantics).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    -- Who owns the key. Keys are only unique per client; until JWT auth (Phase 4) every caller is 'anonymous'.
    scope            VARCHAR(64)   NOT NULL,
    idempotency_key  VARCHAR(255)  NOT NULL,
    -- SHA-256 of method + path + body: the same key with a different request is rejected.
    request_hash     VARCHAR(64)   NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    response_status  INT,
    -- Stored verbatim so a replay is byte-for-byte identical to the original response.
    response_body    TEXT,
    response_location VARCHAR(512),
    -- Identifies the request that currently owns the key. The business transaction only commits if it
    -- still owns the key, so a request whose lease was taken over can never complete a second execution.
    lock_token       UUID          NOT NULL,
    -- While PROCESSING: after this instant the owner is presumed dead and another request may take over.
    locked_until     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,

    PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT chk_idempotency_keys_status CHECK (status IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT chk_idempotency_keys_response
        CHECK ((status = 'COMPLETED') = (response_status IS NOT NULL AND response_body IS NOT NULL))
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);

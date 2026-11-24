-- Paying moderator allocations out with Stripe Connect, test mode first (D-046, D-047).
-- Howlers stores only the connected account id and Stripe's status flags, never bank or identity details: Stripe's
-- hosted onboarding collects those. Payouts are Stripe Transfers from the platform balance to the connected account.

-- 1. One Stripe Express account per moderator and mode (an account made in test mode can't be used live).
CREATE TABLE funding_connected_accounts (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users (id),
    provider            VARCHAR(20)  NOT NULL,
    mode                VARCHAR(4)   NOT NULL CHECK (mode IN ('test', 'live')),
    account_id          VARCHAR(64)  NOT NULL UNIQUE,
    -- PENDING: created, onboarding not finished. REQUIREMENTS_DUE: Stripe needs more information before it can pay out.
    -- ENABLED: transfers and payouts are enabled, so held shares can be sent.
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REQUIREMENTS_DUE', 'ENABLED')),
    details_submitted   BOOLEAN      NOT NULL DEFAULT FALSE,
    charges_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    payouts_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    transfers_active    BOOLEAN      NOT NULL DEFAULT FALSE,
    requirements_due    INTEGER      NOT NULL DEFAULT 0,
    disabled_reason     VARCHAR(100),
    -- The last payout from the connected account to the moderator's bank (payout.* events), for the moderator's status.
    last_payout_status  VARCHAR(20),
    last_payout_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    synced_at           TIMESTAMPTZ,
    UNIQUE (user_id, provider, mode)
);

-- 2. One payout per allocation with a positive amount, for pools taken through Stripe. Unlike allocations this row has
--    a state (HELD -> PAID, FAILED -> retried, PAID -> REVERSED); every movement of money is an append-only ledger row.
CREATE TABLE funding_payouts (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    allocation_id       BIGINT       NOT NULL UNIQUE REFERENCES funding_allocations (id),
    pool_id             BIGINT       NOT NULL REFERENCES funding_pools (id),
    moderator_id        BIGINT       NOT NULL REFERENCES users (id),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3)      NOT NULL,
    provider            VARCHAR(20)  NOT NULL,
    mode                VARCHAR(4)   NOT NULL CHECK (mode IN ('test', 'live')),
    status              VARCHAR(10)  NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD', 'FAILED', 'PAID', 'REVERSED')),
    status_reason       VARCHAR(300),
    connected_account   VARCHAR(64),
    transfer_id         VARCHAR(64)  UNIQUE,
    transferred_minor   BIGINT       NOT NULL DEFAULT 0 CHECK (transferred_minor >= 0),
    reversed_minor      BIGINT       NOT NULL DEFAULT 0 CHECK (reversed_minor >= 0),
    -- The idempotency key is howlers-payout-{id}-{attempt}. The attempt only moves on after Stripe definitively refused a
    -- transfer; after an unknown outcome (timeout) the same key is reused, so a retry can never create a second transfer.
    attempt             INTEGER      NOT NULL DEFAULT 0,
    failures            INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    paid_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (transferred_minor <= amount_minor AND reversed_minor <= transferred_minor),
    CHECK ((status IN ('PAID', 'REVERSED')) = (transfer_id IS NOT NULL))
);
CREATE INDEX ix_funding_payouts_pool ON funding_payouts (pool_id);
CREATE INDEX ix_funding_payouts_moderator ON funding_payouts (moderator_id, id DESC);
CREATE INDEX ix_funding_payouts_open ON funding_payouts (moderator_id) WHERE status IN ('HELD', 'FAILED');

-- 3. Things an admin must look at by hand: a refund after a payout (no automatic claw-back), a reversed transfer, a
--    transfer that keeps failing. ref makes each flag idempotent (a webhook replay doesn't raise it twice).
CREATE TABLE funding_review_flags (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind             VARCHAR(30)  NOT NULL CHECK (kind IN ('REFUND_AFTER_PAYOUT', 'TRANSFER_REVERSED', 'TRANSFER_FAILING')),
    ref              VARCHAR(120) NOT NULL UNIQUE,
    pool_id          BIGINT       NOT NULL REFERENCES funding_pools (id),
    payout_id        BIGINT REFERENCES funding_payouts (id),
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor >= 0),
    currency         CHAR(3)      NOT NULL,
    mode             VARCHAR(4)   NOT NULL CHECK (mode IN ('demo', 'test', 'live')),
    detail           VARCHAR(500) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    resolved_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolution_note  VARCHAR(500)
);
CREATE INDEX ix_funding_review_open ON funding_review_flags (id DESC) WHERE resolved_at IS NULL;

-- 4. The ledger learns PAYOUT (moderator:{id} -> connected:{id}) and PAYOUT_REVERSAL (back). external_ref names the
--    Stripe transfer (or the reversal state), unique, so a transfer or a reversal is booked at most once.
ALTER TABLE funding_ledger ADD COLUMN payout_id BIGINT REFERENCES funding_payouts (id);
ALTER TABLE funding_ledger ADD COLUMN external_ref VARCHAR(120) UNIQUE;
ALTER TABLE funding_ledger DROP CONSTRAINT IF EXISTS ck_funding_ledger_entry_type;
ALTER TABLE funding_ledger DROP CONSTRAINT IF EXISTS ck_funding_ledger_entry_refs;
ALTER TABLE funding_ledger ADD CONSTRAINT ck_funding_ledger_entry_type
    CHECK (entry_type IN ('CONTRIBUTION', 'ALLOCATION', 'REFUND', 'PAYOUT', 'PAYOUT_REVERSAL'));
ALTER TABLE funding_ledger ADD CONSTRAINT ck_funding_ledger_entry_refs CHECK (
       (entry_type = 'CONTRIBUTION' AND contribution_id IS NOT NULL AND allocation_id IS NULL AND refund_id IS NULL AND payout_id IS NULL)
    OR (entry_type = 'ALLOCATION' AND allocation_id IS NOT NULL AND contribution_id IS NULL AND refund_id IS NULL AND payout_id IS NULL)
    OR (entry_type = 'REFUND' AND refund_id IS NOT NULL AND contribution_id IS NULL AND allocation_id IS NULL AND payout_id IS NULL)
    OR (entry_type IN ('PAYOUT', 'PAYOUT_REVERSAL') AND payout_id IS NOT NULL AND external_ref IS NOT NULL
        AND contribution_id IS NULL AND allocation_id IS NULL AND refund_id IS NULL));
ALTER TABLE funding_ledger ALTER COLUMN entry_type TYPE VARCHAR(16);
CREATE INDEX ix_funding_ledger_payout ON funding_ledger (payout_id) WHERE payout_id IS NOT NULL;

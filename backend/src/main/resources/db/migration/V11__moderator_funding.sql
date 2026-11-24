-- Phase 8: moderator funding (demo mode). See docs/DECISIONS.md D-022.
-- Money is always an integer number of minor units (cents) plus an ISO 4217 currency code. Never floating point.

-- Allocation policy per community, set by the owner. Applied when a pool's period closes.
CREATE TABLE funding_policies (
    community_id   BIGINT       PRIMARY KEY REFERENCES communities (id) ON DELETE CASCADE,
    policy         VARCHAR(10)  NOT NULL DEFAULT 'EQUAL' CHECK (policy IN ('EQUAL', 'ACTIVITY', 'MANUAL')),
    activity_cap   INTEGER      NOT NULL DEFAULT 50 CHECK (activity_cap BETWEEN 1 AND 1000),
    updated_by     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE funding_pools (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id    BIGINT        NOT NULL REFERENCES communities (id),
    created_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    title           VARCHAR(120)  NOT NULL,
    purpose         VARCHAR(2000) NOT NULL,
    currency        CHAR(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    goal_minor      BIGINT        NOT NULL CHECK (goal_minor > 0),
    period_start    TIMESTAMPTZ   NOT NULL,
    period_end      TIMESTAMPTZ   NOT NULL,
    -- OPEN: accepting contributions. AWAITING_ALLOCATION: period over, MANUAL policy, owner must allocate.
    -- CLOSED: allocations recorded (immutable).
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'AWAITING_ALLOCATION', 'CLOSED')),
    provider        VARCHAR(20)   NOT NULL,
    demo            BOOLEAN       NOT NULL,
    closed_policy   VARCHAR(10),
    closed_note     VARCHAR(1000),
    closed_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    closed_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (period_end > period_start)
);
CREATE INDEX ix_funding_pools_community ON funding_pools (community_id, id DESC);
CREATE INDEX ix_funding_pools_due ON funding_pools (period_end) WHERE status = 'OPEN';

-- Monthly recurring contributions to one pool, until cancelled or the pool's period ends.
CREATE TABLE funding_subscriptions (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pool_id          BIGINT       NOT NULL REFERENCES funding_pools (id),
    contributor_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency         CHAR(3)      NOT NULL,
    show_name        BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED', 'ENDED')),
    provider         VARCHAR(20)  NOT NULL,
    provider_ref     VARCHAR(100) NOT NULL UNIQUE,
    demo             BOOLEAN      NOT NULL,
    cycles_charged   INTEGER      NOT NULL DEFAULT 0,
    next_charge_at   TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    cancelled_by     VARCHAR(10) CHECK (cancelled_by IN ('USER', 'PROVIDER', 'SYSTEM')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_funding_subs_due ON funding_subscriptions (next_charge_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_funding_subs_user ON funding_subscriptions (contributor_id, id DESC);

-- One row per successful charge. cycle is set for recurring charges so the scheduler can never charge a cycle twice.
CREATE TABLE funding_contributions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pool_id             BIGINT       NOT NULL REFERENCES funding_pools (id),
    contributor_id      BIGINT REFERENCES users (id),  -- users are soft-deleted (anonymised), never removed
    subscription_id     BIGINT REFERENCES funding_subscriptions (id),
    cycle               INTEGER,
    kind                VARCHAR(10)  NOT NULL CHECK (kind IN ('ONE_TIME', 'RECURRING')),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3)      NOT NULL,
    show_name           BOOLEAN      NOT NULL DEFAULT FALSE,
    provider            VARCHAR(20)  NOT NULL,
    provider_charge_id  VARCHAR(100) NOT NULL UNIQUE,
    demo                BOOLEAN      NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((kind = 'RECURRING') = (subscription_id IS NOT NULL AND cycle IS NOT NULL)),
    UNIQUE (subscription_id, cycle)
);
CREATE INDEX ix_funding_contrib_pool ON funding_contributions (pool_id, id);
CREATE INDEX ix_funding_contrib_user ON funding_contributions (contributor_id, id DESC);

-- What each moderator received when a pool closed, and under which policy.
CREATE TABLE funding_allocations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pool_id          BIGINT       NOT NULL REFERENCES funding_pools (id),
    moderator_id     BIGINT       NOT NULL REFERENCES users (id),
    policy           VARCHAR(10)  NOT NULL CHECK (policy IN ('EQUAL', 'ACTIVITY', 'MANUAL')),
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor >= 0),
    currency         CHAR(3)      NOT NULL,
    activity_score   INTEGER,
    reports_resolved INTEGER,
    appeals_handled  INTEGER,
    note             VARCHAR(1000),
    demo             BOOLEAN      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (pool_id, moderator_id)
);

-- Append-only, double-entry style ledger. Each row moves amount_minor from one account to another:
--   CONTRIBUTION: contributors      -> pool:{id}
--   ALLOCATION:   pool:{id}         -> moderator:{userId}
CREATE TABLE funding_ledger (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pool_id          BIGINT       NOT NULL REFERENCES funding_pools (id),
    entry_type       VARCHAR(12)  NOT NULL CHECK (entry_type IN ('CONTRIBUTION', 'ALLOCATION')),
    debit_account    VARCHAR(40)  NOT NULL,
    credit_account   VARCHAR(40)  NOT NULL,
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency         CHAR(3)      NOT NULL,
    contribution_id  BIGINT UNIQUE REFERENCES funding_contributions (id),
    allocation_id    BIGINT UNIQUE REFERENCES funding_allocations (id),
    demo             BOOLEAN      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (debit_account <> credit_account),
    CHECK ((entry_type = 'CONTRIBUTION' AND contribution_id IS NOT NULL AND allocation_id IS NULL)
        OR (entry_type = 'ALLOCATION' AND allocation_id IS NOT NULL AND contribution_id IS NULL))
);
CREATE INDEX ix_funding_ledger_pool ON funding_ledger (pool_id, id);

-- Ledger rows, allocations and contributions can never be changed or deleted, even by a buggy service.
CREATE FUNCTION funding_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_funding_ledger_append_only BEFORE UPDATE OR DELETE ON funding_ledger
    FOR EACH ROW EXECUTE FUNCTION funding_append_only();
CREATE TRIGGER trg_funding_allocations_append_only BEFORE UPDATE OR DELETE ON funding_allocations
    FOR EACH ROW EXECUTE FUNCTION funding_append_only();
CREATE TRIGGER trg_funding_contributions_append_only BEFORE UPDATE OR DELETE ON funding_contributions
    FOR EACH ROW EXECUTE FUNCTION funding_append_only();

-- Webhook idempotency: an event id is processed at most once per provider.
CREATE TABLE funding_webhook_events (
    provider     VARCHAR(20)  NOT NULL,
    event_id     VARCHAR(100) NOT NULL,
    event_type   VARCHAR(60)  NOT NULL,
    outcome      VARCHAR(200) NOT NULL DEFAULT '',
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_id)
);

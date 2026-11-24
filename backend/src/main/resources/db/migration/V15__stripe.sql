-- Stripe as a real payment provider for moderator funding (D-034, D-035). Test mode first; live keys need an explicit
-- compliance acknowledgement. The ledger stays append-only: refunds are new rows, never edits.

-- 1. Every money row records the mode it was made in: demo (simulated), test (Stripe test mode) or live (real money).
--    "demo" keeps its old meaning for clients: true whenever no real money moved (demo and test).
ALTER TABLE funding_pools         ADD COLUMN mode VARCHAR(4) NOT NULL DEFAULT 'demo' CHECK (mode IN ('demo', 'test', 'live'));
ALTER TABLE funding_subscriptions ADD COLUMN mode VARCHAR(4) NOT NULL DEFAULT 'demo' CHECK (mode IN ('demo', 'test', 'live'));
ALTER TABLE funding_contributions ADD COLUMN mode VARCHAR(4) NOT NULL DEFAULT 'demo' CHECK (mode IN ('demo', 'test', 'live'));
ALTER TABLE funding_allocations   ADD COLUMN mode VARCHAR(4) NOT NULL DEFAULT 'demo' CHECK (mode IN ('demo', 'test', 'live'));
ALTER TABLE funding_ledger        ADD COLUMN mode VARCHAR(4) NOT NULL DEFAULT 'demo' CHECK (mode IN ('demo', 'test', 'live'));
ALTER TABLE funding_pools         ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE funding_subscriptions ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE funding_contributions ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE funding_allocations   ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE funding_ledger        ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE funding_pools         ADD CONSTRAINT ck_funding_pools_mode_demo CHECK (demo = (mode <> 'live'));
ALTER TABLE funding_subscriptions ADD CONSTRAINT ck_funding_subs_mode_demo CHECK (demo = (mode <> 'live'));
ALTER TABLE funding_contributions ADD CONSTRAINT ck_funding_contrib_mode_demo CHECK (demo = (mode <> 'live'));
ALTER TABLE funding_allocations   ADD CONSTRAINT ck_funding_alloc_mode_demo CHECK (demo = (mode <> 'live'));
ALTER TABLE funding_ledger        ADD CONSTRAINT ck_funding_ledger_mode_demo CHECK (demo = (mode <> 'live'));

-- 2. The provider's payment object behind a contribution, when it differs from provider_charge_id. One-time Stripe
--    contributions use the PaymentIntent id as the charge id; recurring cycles use the invoice id and keep the
--    PaymentIntent here when the event carries it, so a refund (which names the PaymentIntent) finds its contribution.
ALTER TABLE funding_contributions ADD COLUMN provider_payment_ref VARCHAR(100);
CREATE INDEX ix_funding_contrib_payment_ref ON funding_contributions (provider, provider_payment_ref) WHERE provider_payment_ref IS NOT NULL;

-- 3. Provider-driven subscriptions: Stripe bills each cycle itself, so next_charge_at stays NULL and the demo job never
--    charges them. A failed renewal is shown until the next paid invoice.
ALTER TABLE funding_subscriptions ADD COLUMN payment_failed_at TIMESTAMPTZ;

-- 4. Pending contributions: a hosted checkout that hasn't been confirmed yet. They never count towards a pool. Only a
--    confirmation (the checkout.session.completed webhook, or the server-side check on return) turns one into a
--    funding_contributions row (plus its ledger row), and it does so at most once.
CREATE TABLE funding_pending_contributions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pool_id              BIGINT       NOT NULL REFERENCES funding_pools (id),
    contributor_id       BIGINT       NOT NULL REFERENCES users (id),
    kind                 VARCHAR(10)  NOT NULL CHECK (kind IN ('ONE_TIME', 'RECURRING')),
    amount_minor         BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency             CHAR(3)      NOT NULL,
    show_name            BOOLEAN      NOT NULL DEFAULT FALSE,
    provider             VARCHAR(20)  NOT NULL,
    mode                 VARCHAR(4)   NOT NULL CHECK (mode IN ('demo', 'test', 'live')),
    checkout_session_id  VARCHAR(255) UNIQUE,
    status               VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'FAILED')),
    status_reason        VARCHAR(200),
    contribution_id      BIGINT REFERENCES funding_contributions (id),
    subscription_id      BIGINT REFERENCES funding_subscriptions (id),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at          TIMESTAMPTZ
);
CREATE INDEX ix_funding_pending_user ON funding_pending_contributions (contributor_id, id DESC);

-- 5. Refunds reverse a contribution with new rows (a refund row and a REFUND ledger row: pool -> contributors).
CREATE TABLE funding_refunds (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pool_id              BIGINT       NOT NULL REFERENCES funding_pools (id),
    contribution_id      BIGINT       NOT NULL REFERENCES funding_contributions (id),
    amount_minor         BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency             CHAR(3)      NOT NULL,
    provider             VARCHAR(20)  NOT NULL,
    -- Derived from the provider's charge and its cumulative refunded amount, so the same refund state is recorded once.
    provider_refund_ref  VARCHAR(120) NOT NULL UNIQUE,
    demo                 BOOLEAN      NOT NULL,
    mode                 VARCHAR(4)   NOT NULL CHECK (mode IN ('demo', 'test', 'live')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (demo = (mode <> 'live'))
);
CREATE INDEX ix_funding_refunds_contribution ON funding_refunds (contribution_id);
CREATE INDEX ix_funding_refunds_pool ON funding_refunds (pool_id, id);
CREATE TRIGGER trg_funding_refunds_append_only BEFORE UPDATE OR DELETE ON funding_refunds
    FOR EACH ROW EXECUTE FUNCTION funding_append_only();

-- 6. The ledger learns a third entry type. The old unnamed CHECKs that mention entry_type are replaced by named ones.
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'funding_ledger'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%entry_type%'
    LOOP
        EXECUTE format('ALTER TABLE funding_ledger DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;
ALTER TABLE funding_ledger ADD COLUMN refund_id BIGINT UNIQUE REFERENCES funding_refunds (id);
ALTER TABLE funding_ledger ADD CONSTRAINT ck_funding_ledger_entry_type CHECK (entry_type IN ('CONTRIBUTION', 'ALLOCATION', 'REFUND'));
ALTER TABLE funding_ledger ADD CONSTRAINT ck_funding_ledger_entry_refs CHECK (
       (entry_type = 'CONTRIBUTION' AND contribution_id IS NOT NULL AND allocation_id IS NULL AND refund_id IS NULL)
    OR (entry_type = 'ALLOCATION' AND allocation_id IS NOT NULL AND contribution_id IS NULL AND refund_id IS NULL)
    OR (entry_type = 'REFUND' AND refund_id IS NOT NULL AND contribution_id IS NULL AND allocation_id IS NULL));

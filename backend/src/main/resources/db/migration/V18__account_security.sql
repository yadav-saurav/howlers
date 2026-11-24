-- Stronger accounts (D-041, D-042): passkeys, TOTP two-factor authentication, known devices for new-sign-in alerts.
-- Only new tables plus one nullable column on refresh_tokens, so this migration is independent of the others.

-- Per-user security state, created lazily (no row = defaults: no 2FA, alerts on).
CREATE TABLE account_security (
    user_id                BIGINT       PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- WebAuthn user handle: 32 random bytes, never the user id, so a passkey reveals nothing about the account.
    webauthn_user_handle   BYTEA        UNIQUE,
    -- TOTP secrets are AES-256-GCM ciphertext (ACCOUNT_SECRETS_KEY), never plaintext.
    totp_secret_enc        VARCHAR(200),
    totp_enabled_at        TIMESTAMPTZ,
    totp_pending_enc       VARCHAR(200),
    totp_pending_at        TIMESTAMPTZ,
    -- Highest accepted 30-second step: a code (or an older one) can't be used twice.
    totp_last_step         BIGINT,
    mfa_failed_attempts    INTEGER      NOT NULL DEFAULT 0,
    mfa_locked_until       TIMESTAMPTZ,
    new_signin_alerts      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One-time recovery codes, SHA-256 of (user id, normalised code). Plaintext is shown once at creation.
CREATE TABLE totp_recovery_codes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   VARCHAR(64) NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, code_hash)
);

-- Second-step tokens: password accepted, code still required. Not a session; only the hash is stored.
CREATE TABLE mfa_login_challenges (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    attempts    INTEGER     NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_mfa_login_challenges_user ON mfa_login_challenges (user_id);

-- Passkeys: public key (COSE), signature counter and flags. No biometric data ever reaches the server.
CREATE TABLE user_passkeys (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    credential_id    BYTEA        NOT NULL UNIQUE,
    public_key_cose  BYTEA        NOT NULL,
    sign_count       BIGINT       NOT NULL DEFAULT 0,
    name             VARCHAR(60)  NOT NULL,
    transports       VARCHAR(120),
    discoverable     BOOLEAN,
    backup_eligible  BOOLEAN      NOT NULL DEFAULT FALSE,
    backed_up        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Set when an assertion came with a counter that didn't increase (possible clone); flagged passkeys are refused.
    flagged_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at     TIMESTAMPTZ
);
CREATE INDEX ix_user_passkeys_user ON user_passkeys (user_id);

-- Ceremony challenges: single use, short-lived, bound to an HttpOnly cookie of the browser that started them.
CREATE TABLE webauthn_challenges (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id    VARCHAR(64)  NOT NULL UNIQUE,
    kind          VARCHAR(16)  NOT NULL,           -- REGISTER | AUTHENTICATE
    user_id       BIGINT       REFERENCES users (id) ON DELETE CASCADE,
    binding_hash  VARCHAR(64)  NOT NULL,
    request_json  TEXT         NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_webauthn_challenges_expires ON webauthn_challenges (expires_at);

-- Devices a user has signed in from: hash of (device cookie id, browser family, OS family). The IP is kept only as a
-- truncated keyed hash of its network prefix, like the files access log.
CREATE TABLE known_devices (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_hash     VARCHAR(64)  NOT NULL,
    browser_family  VARCHAR(40)  NOT NULL,
    os_family       VARCHAR(40)  NOT NULL,
    network_hash    VARCHAR(16),
    first_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, device_hash)
);

-- How each session was signed in: PASSWORD | PASSWORD_TOTP | PASSWORD_RECOVERY | PASSKEY (null for older sessions).
ALTER TABLE refresh_tokens ADD COLUMN auth_method VARCHAR(20);

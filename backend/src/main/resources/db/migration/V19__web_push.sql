-- Web push subscriptions (D-040): one row per browser/device a user enabled push on.
-- The endpoint is a capability URL issued by the browser's push service; together with p256dh/auth it lets the server
-- encrypt (RFC 8291) and deliver a message to that device only. Rows are deleted when the push service answers
-- 404/410 (subscription gone), after repeated failures, when the user turns push off, or with the account.
CREATE TABLE push_subscriptions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint        VARCHAR(1024) NOT NULL,
    p256dh          VARCHAR(100)  NOT NULL,     -- base64url, uncompressed P-256 point
    auth            VARCHAR(40)   NOT NULL,     -- base64url, 16-byte auth secret
    label           VARCHAR(80)   NOT NULL DEFAULT 'This device',
    -- Categories this device wants: REPLIES, MENTIONS, MESSAGES, MODERATION, FOLLOWERS, ACTIVITY.
    categories      TEXT[]        NOT NULL DEFAULT '{REPLIES,MENTIONS,MESSAGES,MODERATION}',
    failure_count   INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ,                -- last push the push service accepted
    last_failure_at TIMESTAMPTZ
);
-- One owner per endpoint: when someone else signs in on the same browser and enables push, the row moves to them.
CREATE UNIQUE INDEX ux_push_subscriptions_endpoint ON push_subscriptions (endpoint);
CREATE INDEX ix_push_subscriptions_user ON push_subscriptions (user_id);

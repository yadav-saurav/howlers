-- One-to-one direct messages.

ALTER TABLE users ADD COLUMN dm_policy VARCHAR(10) NOT NULL DEFAULT 'EVERYONE'
    CHECK (dm_policy IN ('EVERYONE', 'FOLLOWING', 'NOBODY'));

CREATE TABLE dm_conversations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- "<smaller user id>:<larger user id>": exactly one conversation per pair of people.
    pair_key         VARCHAR(41) NOT NULL UNIQUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at  TIMESTAMPTZ
);

CREATE TABLE dm_participants (
    conversation_id       BIGINT      NOT NULL REFERENCES dm_conversations (id) ON DELETE CASCADE,
    user_id               BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_message_id  BIGINT,
    joined_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX ix_dm_participants_user ON dm_participants (user_id);

CREATE TABLE dm_messages (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id  BIGINT        NOT NULL REFERENCES dm_conversations (id) ON DELETE CASCADE,
    sender_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    body             VARCHAR(4000) NOT NULL DEFAULT '',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);
CREATE INDEX ix_dm_messages_conversation ON dm_messages (conversation_id, id DESC);

CREATE TABLE dm_message_media (
    message_id  BIGINT  NOT NULL REFERENCES dm_messages (id) ON DELETE CASCADE,
    media_id    BIGINT  NOT NULL REFERENCES media_assets (id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    PRIMARY KEY (message_id, media_id)
);

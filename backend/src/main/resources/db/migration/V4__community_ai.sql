-- Phase 5: Community AI. Comments become searchable; conversations and answers are persisted with the exact
-- sources that were shown to the model, so every citation can be audited later.

ALTER TABLE comments ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (to_tsvector('english', left(coalesce(body, ''), 20000))) STORED;
CREATE INDEX ix_comments_search ON comments USING gin (search_vector);

CREATE TABLE ai_conversations (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title         VARCHAR(140) NOT NULL,
    community_id  BIGINT REFERENCES communities (id) ON DELETE SET NULL,   -- optional scope
    post_id       BIGINT REFERENCES posts (id) ON DELETE SET NULL,         -- optional scope (thread Q&A)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_conversations_user ON ai_conversations (user_id, updated_at DESC);

CREATE TABLE ai_messages (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id  BIGINT       NOT NULL REFERENCES ai_conversations (id) ON DELETE CASCADE,
    role             VARCHAR(10)  NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content          TEXT         NOT NULL,
    engine           VARCHAR(60),                         -- provider/model that produced an assistant turn
    intent           VARCHAR(20),
    sources          JSONB        NOT NULL DEFAULT '[]',  -- snapshot of retrieved sources, in citation order
    follow_ups       JSONB        NOT NULL DEFAULT '[]',
    notice           VARCHAR(400),                        -- e.g. "sources limited", "offline mode"
    feedback         SMALLINT     NOT NULL DEFAULT 0 CHECK (feedback IN (-1, 0, 1)),
    input_tokens     INTEGER,
    output_tokens    INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_messages_conversation ON ai_messages (conversation_id, id);

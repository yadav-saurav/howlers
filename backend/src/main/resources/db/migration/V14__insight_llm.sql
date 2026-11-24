-- LLM-backed Thread Digest and Context Mode stance suggestions (D-031 to D-033).

-- AI-suggested stances, kept apart from reader labels (comment_stances). Reader labels always win; a row here is
-- only used while its content_hash still matches the comment body and the post it is judged against.
CREATE TABLE comment_ai_stances (
    comment_id    BIGINT      PRIMARY KEY REFERENCES comments (id) ON DELETE CASCADE,
    content_hash  VARCHAR(64) NOT NULL,
    stance        VARCHAR(12) NOT NULL CHECK (stance IN ('SUPPORT','OPPOSE','NEUTRAL','QUESTION','EVIDENCE','EXPERIENCE')),
    engine        VARCHAR(40) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Digest cache: how it was produced, whether it is a fallback, what it cited (for removal/edit checks) and its cost.
ALTER TABLE thread_digests
    ADD COLUMN engine_note    TEXT,
    ADD COLUMN notice         TEXT,
    ADD COLUMN fallback       BOOLEAN  NOT NULL DEFAULT FALSE,
    ADD COLUMN cited_ids      BIGINT[] NOT NULL DEFAULT '{}',
    ADD COLUMN cited_version  VARCHAR(64),
    ADD COLUMN input_tokens   INTEGER,
    ADD COLUMN output_tokens  INTEGER;

-- One row per model call made for Context Mode or digests (Community AI records tokens per message instead).
CREATE TABLE insight_llm_usage (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation      VARCHAR(16) NOT NULL CHECK (operation IN ('DIGEST','STANCES')),
    post_id        BIGINT      REFERENCES posts (id) ON DELETE SET NULL,
    user_id        BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    engine         VARCHAR(40) NOT NULL,
    outcome        VARCHAR(12) NOT NULL,
    input_tokens   INTEGER     NOT NULL DEFAULT 0,
    output_tokens  INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_insight_llm_usage_user ON insight_llm_usage (user_id, created_at DESC);

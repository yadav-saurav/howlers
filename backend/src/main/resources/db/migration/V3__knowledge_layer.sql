-- Phase 4 knowledge layer: comment stances (Context Mode), cached digests, Smart Collections.

-- Each user may label any comment with one stance; aggregate = weighted majority (author ×2).
CREATE TABLE comment_stances (
    comment_id  BIGINT      NOT NULL REFERENCES comments (id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    stance      VARCHAR(12) NOT NULL CHECK (stance IN ('SUPPORT','OPPOSE','NEUTRAL','QUESTION','EVIDENCE','EXPERIENCE')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id)
);
CREATE INDEX ix_comment_stances_comment ON comment_stances (comment_id);

-- Digest cache keyed by (post, length, focus). Stale when the thread grew materially since generation.
CREATE TABLE thread_digests (
    post_id        BIGINT       NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    length         VARCHAR(12)  NOT NULL,
    focus          VARCHAR(12)  NOT NULL,
    engine         VARCHAR(40)  NOT NULL,
    payload        JSONB        NOT NULL,
    comment_count  INTEGER      NOT NULL,
    generated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, length, focus)
);

CREATE TABLE collections (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(60)  NOT NULL,
    description  VARCHAR(500) NOT NULL DEFAULT '',
    shared       BOOLEAN      NOT NULL DEFAULT FALSE,     -- private by default
    share_token  VARCHAR(32)  UNIQUE,                    -- unguessable read-only link when shared
    position     INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_collections_owner_name ON collections (owner_id, lower(name));

CREATE TABLE collection_items (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    collection_id  BIGINT       NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    item_type      VARCHAR(8)   NOT NULL CHECK (item_type IN ('POST','COMMENT')),
    post_id        BIGINT       NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    comment_id     BIGINT       REFERENCES comments (id) ON DELETE CASCADE,
    note           VARCHAR(2000) NOT NULL DEFAULT '',
    tags           TEXT[]       NOT NULL DEFAULT '{}',
    position       DOUBLE PRECISION NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((item_type = 'POST' AND comment_id IS NULL) OR (item_type = 'COMMENT' AND comment_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_collection_items_target ON collection_items (collection_id, post_id, coalesce(comment_id, 0));
CREATE INDEX ix_collection_items_order ON collection_items (collection_id, position);
CREATE INDEX ix_collection_items_tags ON collection_items USING gin (tags);

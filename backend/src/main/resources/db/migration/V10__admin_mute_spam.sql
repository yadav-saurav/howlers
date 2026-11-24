-- Phase 3 (rest): site administration, mutes and write-time spam heuristics. See docs/DECISIONS.md D-021.

-- ---------------------------------------------------------------- community status (site admins)
-- ACTIVE: normal. QUARANTINED: still reachable by link, but left out of Popular/All, trending and search, with a
-- banner. BANNED: closed; only platform admins can open it.
ALTER TABLE communities ADD COLUMN status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE', 'QUARANTINED', 'BANNED'));
ALTER TABLE communities ADD COLUMN status_reason VARCHAR(300);
CREATE INDEX ix_communities_status ON communities (status) WHERE status <> 'ACTIVE';

-- ---------------------------------------------------------------- account suspension details
ALTER TABLE users ADD COLUMN suspended_reason VARCHAR(300);
ALTER TABLE users ADD COLUMN suspended_at TIMESTAMPTZ;
CREATE INDEX ix_users_created ON users (created_at);

-- ---------------------------------------------------------------- held content and spam signals
-- Held content is stored as removed = TRUE AND pending = TRUE, so every existing "removed = FALSE" filter (feeds,
-- AI retrieval, digests, collections, votes) already hides it. Only the author and moderators can see it.
ALTER TABLE posts ADD COLUMN pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE posts ADD COLUMN content_hash VARCHAR(64);   -- sha256 of normalised title + body
ALTER TABLE posts ADD COLUMN link_hash VARCHAR(64);      -- sha256 of the normalised link (LINK posts)
ALTER TABLE posts ADD COLUMN link_count SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE comments ADD COLUMN pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE comments ADD COLUMN link_count SMALLINT NOT NULL DEFAULT 0;
CREATE INDEX ix_posts_content_hash ON posts (content_hash, created_at DESC) WHERE content_hash IS NOT NULL;
CREATE INDEX ix_posts_pending ON posts (community_id) WHERE pending;
CREATE INDEX ix_comments_pending ON comments (post_id) WHERE pending;

CREATE TABLE spam_flags (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id  BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    author_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    target_type   VARCHAR(8)   NOT NULL CHECK (target_type IN ('POST', 'COMMENT')),
    post_id       BIGINT REFERENCES posts (id) ON DELETE CASCADE,       -- NULL when the content was rejected
    comment_id    BIGINT REFERENCES comments (id) ON DELETE CASCADE,
    rules         VARCHAR(100) NOT NULL,                                 -- comma list: LINK_VELOCITY,DUPLICATE,BURST,MENTIONS
    outcome       VARCHAR(10)  NOT NULL CHECK (outcome IN ('HELD', 'REJECTED')),
    details       VARCHAR(500) NOT NULL,
    excerpt       VARCHAR(300) NOT NULL DEFAULT '',
    status        VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'APPROVED', 'REMOVED', 'CLOSED')),
    resolved_by   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_spam_flags_community ON spam_flags (community_id, status, id DESC);
CREATE INDEX ix_spam_flags_status ON spam_flags (status, id DESC);
CREATE INDEX ix_spam_flags_post ON spam_flags (post_id, comment_id) WHERE status = 'OPEN';

-- ---------------------------------------------------------------- mutes (private, one-directional)
CREATE TABLE user_mutes (
    muter_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    muted_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (muter_id, muted_id),
    CHECK (muter_id <> muted_id)
);

CREATE TABLE community_mutes (
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    community_id  BIGINT      NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, community_id)
);

-- ---------------------------------------------------------------- site admin audit log (append-only)
CREATE TABLE admin_actions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_id             BIGINT REFERENCES users (id) ON DELETE SET NULL,
    action               VARCHAR(30)  NOT NULL,
    target_user_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    target_community_id  BIGINT REFERENCES communities (id) ON DELETE SET NULL,
    flag_id              BIGINT REFERENCES spam_flags (id) ON DELETE SET NULL,
    reason               VARCHAR(300),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_actions_time ON admin_actions (id DESC);

-- Rows can't be deleted, and only foreign keys may change (to NULL, when a referenced row is deleted).
CREATE FUNCTION strata_admin_actions_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'admin_actions is append-only';
    END IF;
    IF NEW.action IS DISTINCT FROM OLD.action OR NEW.reason IS DISTINCT FROM OLD.reason
       OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'admin_actions is append-only';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_admin_actions_append_only BEFORE UPDATE OR DELETE ON admin_actions
    FOR EACH ROW EXECUTE FUNCTION strata_admin_actions_append_only();

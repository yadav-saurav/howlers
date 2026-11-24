-- Strata core schema: identity, communities, posts, comments, votes, notifications.
-- Conventions: bigint identity PKs, timestamptz, soft deletes via deleted_at, counters denormalised
-- and maintained transactionally by the service layer.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- array_to_string() is only STABLE, which generated columns reject; for text[] it is in fact immutable.
CREATE FUNCTION strata_tags_text(tags TEXT[]) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT array_to_string(tags, ' ') $$;

-- ---------------------------------------------------------------- identity
CREATE TABLE users (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username             VARCHAR(21)  NOT NULL,
    email                VARCHAR(254) NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    display_name         VARCHAR(50),
    bio                  VARCHAR(500),
    avatar_url           VARCHAR(500),
    banner_url           VARCHAR(500),
    role                 VARCHAR(16)  NOT NULL DEFAULT 'USER',
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | DEACTIVATED | DELETED | SUSPENDED
    email_verified       BOOLEAN      NOT NULL DEFAULT FALSE,
    onboarding_completed BOOLEAN      NOT NULL DEFAULT FALSE,
    post_karma           INTEGER      NOT NULL DEFAULT 0,
    comment_karma        INTEGER      NOT NULL DEFAULT 0,
    follower_count       INTEGER      NOT NULL DEFAULT 0,
    following_count      INTEGER      NOT NULL DEFAULT 0,
    -- settings (1:1 with user, kept inline to avoid a join on every request)
    theme                VARCHAR(8)   NOT NULL DEFAULT 'dark',
    show_nsfw            BOOLEAN      NOT NULL DEFAULT FALSE,
    profile_public       BOOLEAN      NOT NULL DEFAULT TRUE,
    show_communities     BOOLEAN      NOT NULL DEFAULT TRUE,
    email_notifications  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_users_username ON users (lower(username));
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE INDEX ix_users_username_trgm ON users USING gin (lower(username) gin_trgm_ops);

-- A refresh token row = one rotation step; family_id = one login session (device).
CREATE TABLE refresh_tokens (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id     UUID         NOT NULL,
    token_hash    VARCHAR(64)     NOT NULL UNIQUE,
    expires_at    TIMESTAMPTZ  NOT NULL,
    revoked_at    TIMESTAMPTZ,
    rotated_at    TIMESTAMPTZ,
    user_agent    VARCHAR(300),
    ip            VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE email_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     VARCHAR(16) NOT NULL,          -- VERIFY_EMAIL | RESET_PASSWORD
    token_hash  VARCHAR(64)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_email_tokens_user ON email_tokens (user_id, purpose);

CREATE TABLE topics (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(60)  NOT NULL,
    description VARCHAR(300)
);

CREATE TABLE user_topics (
    user_id   BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    topic_id  BIGINT NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, topic_id)
);

CREATE TABLE user_follows (
    follower_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id <> followee_id)
);
CREATE INDEX ix_user_follows_followee ON user_follows (followee_id);

-- ---------------------------------------------------------------- communities
CREATE TABLE communities (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug          VARCHAR(24)  NOT NULL,
    name          VARCHAR(60)  NOT NULL,
    description   VARCHAR(1000) NOT NULL DEFAULT '',
    topic_id      BIGINT REFERENCES topics (id) ON DELETE SET NULL,
    icon_url      VARCHAR(500),
    banner_url    VARCHAR(500),
    visibility    VARCHAR(12)  NOT NULL DEFAULT 'PUBLIC',     -- PUBLIC | RESTRICTED | PRIVATE
    tags          TEXT[]       NOT NULL DEFAULT '{}',
    member_count  INTEGER      NOT NULL DEFAULT 0,
    post_count    INTEGER      NOT NULL DEFAULT 0,
    created_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(name, '') || ' ' || slug), 'A') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'B')) STORED
);
CREATE UNIQUE INDEX ux_communities_slug ON communities (lower(slug));
CREATE INDEX ix_communities_topic ON communities (topic_id);
CREATE INDEX ix_communities_members ON communities (member_count DESC, id DESC);
CREATE INDEX ix_communities_search ON communities USING gin (search_vector);

CREATE TABLE community_members (
    community_id BIGINT      NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(12) NOT NULL DEFAULT 'MEMBER',  -- OWNER | ADMIN | MODERATOR | CONTRIBUTOR | MEMBER
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (community_id, user_id)
);
CREATE INDEX ix_community_members_user ON community_members (user_id);
CREATE INDEX ix_community_members_joined ON community_members (community_id, joined_at);

CREATE TABLE community_rules (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    position     INTEGER      NOT NULL,
    title        VARCHAR(100) NOT NULL,
    description  VARCHAR(500) NOT NULL DEFAULT ''
);
CREATE INDEX ix_community_rules_community ON community_rules (community_id, position);

CREATE TABLE community_flairs (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id BIGINT      NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    text         VARCHAR(40) NOT NULL,
    color        VARCHAR(7)  NOT NULL DEFAULT '#3FB68B'
);
CREATE INDEX ix_community_flairs_community ON community_flairs (community_id);

-- ---------------------------------------------------------------- media
CREATE TABLE media_assets (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    storage_key   VARCHAR(200) NOT NULL UNIQUE,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    width         INTEGER,
    height        INTEGER,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- posts
CREATE TABLE posts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id   BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    author_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    type           VARCHAR(8)   NOT NULL,              -- TEXT | LINK | IMAGE | POLL
    title          VARCHAR(300) NOT NULL,
    body_html      TEXT         NOT NULL DEFAULT '',
    body_text      TEXT         NOT NULL DEFAULT '',
    url            VARCHAR(2000),
    flair_id       BIGINT REFERENCES community_flairs (id) ON DELETE SET NULL,
    tags           TEXT[]       NOT NULL DEFAULT '{}',
    upvotes        INTEGER      NOT NULL DEFAULT 0,
    downvotes      INTEGER      NOT NULL DEFAULT 0,
    score          INTEGER      NOT NULL DEFAULT 0,
    comment_count  INTEGER      NOT NULL DEFAULT 0,
    hot_rank       DOUBLE PRECISION NOT NULL DEFAULT 0,
    pinned         BOOLEAN      NOT NULL DEFAULT FALSE,
    locked         BOOLEAN      NOT NULL DEFAULT FALSE,
    removed        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    edited_at      TIMESTAMPTZ,
    deleted_at     TIMESTAMPTZ,
    search_vector  TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', strata_tags_text(tags)), 'B') ||
        setweight(to_tsvector('english', left(coalesce(body_text, ''), 20000)), 'C')) STORED
);
CREATE INDEX ix_posts_community_hot ON posts (community_id, hot_rank DESC, id DESC) WHERE deleted_at IS NULL AND removed = FALSE;
CREATE INDEX ix_posts_community_new ON posts (community_id, created_at DESC, id DESC) WHERE deleted_at IS NULL AND removed = FALSE;
CREATE INDEX ix_posts_hot ON posts (hot_rank DESC, id DESC) WHERE deleted_at IS NULL AND removed = FALSE;
CREATE INDEX ix_posts_new ON posts (created_at DESC, id DESC) WHERE deleted_at IS NULL AND removed = FALSE;
CREATE INDEX ix_posts_score ON posts (score DESC, id DESC) WHERE deleted_at IS NULL AND removed = FALSE;
CREATE INDEX ix_posts_author ON posts (author_id, created_at DESC);
CREATE INDEX ix_posts_search ON posts USING gin (search_vector);
CREATE INDEX ix_posts_tags ON posts USING gin (tags);

CREATE TABLE post_media (
    post_id   BIGINT  NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    media_id  BIGINT  NOT NULL REFERENCES media_assets (id) ON DELETE CASCADE,
    position  INTEGER NOT NULL,
    PRIMARY KEY (post_id, media_id)
);

CREATE TABLE poll_options (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id   BIGINT       NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    position  INTEGER      NOT NULL,
    label     VARCHAR(120) NOT NULL,
    votes     INTEGER      NOT NULL DEFAULT 0
);
CREATE INDEX ix_poll_options_post ON poll_options (post_id, position);

CREATE TABLE polls (
    post_id    BIGINT PRIMARY KEY REFERENCES posts (id) ON DELETE CASCADE,
    multiple   BOOLEAN     NOT NULL DEFAULT FALSE,
    closes_at  TIMESTAMPTZ,
    total_voters INTEGER   NOT NULL DEFAULT 0
);

CREATE TABLE poll_votes (
    option_id  BIGINT      NOT NULL REFERENCES poll_options (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id    BIGINT      NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (option_id, user_id)
);
CREATE INDEX ix_poll_votes_post_user ON poll_votes (post_id, user_id);

CREATE TABLE post_votes (
    post_id    BIGINT      NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    value      SMALLINT    NOT NULL CHECK (value IN (-1, 1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);
CREATE INDEX ix_post_votes_user ON post_votes (user_id);

CREATE TABLE saved_posts (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id    BIGINT      NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);
CREATE INDEX ix_saved_posts_user_time ON saved_posts (user_id, created_at DESC);

CREATE TABLE hidden_posts (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id    BIGINT      NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);

-- ---------------------------------------------------------------- comments (adjacency list + depth)
CREATE TABLE comments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id       BIGINT       NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    parent_id     BIGINT REFERENCES comments (id) ON DELETE CASCADE,
    author_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    depth         SMALLINT     NOT NULL DEFAULT 0,
    body          TEXT         NOT NULL,
    upvotes       INTEGER      NOT NULL DEFAULT 0,
    downvotes     INTEGER      NOT NULL DEFAULT 0,
    score         INTEGER      NOT NULL DEFAULT 0,
    best_rank     DOUBLE PRECISION NOT NULL DEFAULT 0,  -- Wilson lower bound
    controversy   DOUBLE PRECISION NOT NULL DEFAULT 0,
    reply_count   INTEGER      NOT NULL DEFAULT 0,      -- direct children
    removed       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    edited_at     TIMESTAMPTZ,
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX ix_comments_post_top ON comments (post_id, best_rank DESC, id DESC) WHERE parent_id IS NULL;
CREATE INDEX ix_comments_post_new ON comments (post_id, created_at DESC, id DESC) WHERE parent_id IS NULL;
CREATE INDEX ix_comments_parent ON comments (parent_id, best_rank DESC, id DESC);
CREATE INDEX ix_comments_author ON comments (author_id, created_at DESC);

CREATE TABLE comment_votes (
    comment_id BIGINT      NOT NULL REFERENCES comments (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    value      SMALLINT    NOT NULL CHECK (value IN (-1, 1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id)
);
CREATE INDEX ix_comment_votes_user ON comment_votes (user_id);

-- ---------------------------------------------------------------- notifications
CREATE TABLE notifications (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(24)  NOT NULL,
    actor_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    group_key   VARCHAR(100),            -- same key + unread => counter bump instead of new row
    message     VARCHAR(300) NOT NULL,
    link        VARCHAR(300) NOT NULL,
    count       INTEGER      NOT NULL DEFAULT 1,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE UNIQUE INDEX ux_notifications_group_unread ON notifications (user_id, group_key) WHERE read_at IS NULL AND group_key IS NOT NULL;

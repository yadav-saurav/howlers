-- Phase 6: community podcasts. Media bytes live in object storage; only metadata is stored here.

CREATE TABLE podcast_episodes (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id        BIGINT        NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    created_by          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    published_by        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    title               VARCHAR(200)  NOT NULL,
    summary             VARCHAR(2000) NOT NULL DEFAULT '',
    type                VARCHAR(20)   NOT NULL,
    status              VARCHAR(12)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED')),
    media_kind          VARCHAR(5) CHECK (media_kind IN ('AUDIO', 'VIDEO')),
    media_key           VARCHAR(200),
    media_content_type  VARCHAR(60),
    media_bytes         BIGINT,
    duration_seconds    INTEGER,
    thumbnail_url       VARCHAR(500),
    allow_download      BOOLEAN       NOT NULL DEFAULT FALSE,
    script              TEXT,
    script_source       VARCHAR(10) CHECK (script_source IN ('HUMAN', 'AI_DRAFT')),
    script_status       VARCHAR(12)   NOT NULL DEFAULT 'NONE' CHECK (script_status IN ('NONE', 'GENERATING', 'READY', 'FAILED')),
    script_error        VARCHAR(500),
    play_count          INTEGER       NOT NULL DEFAULT 0,
    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_podcast_episodes_published ON podcast_episodes (published_at DESC, id DESC) WHERE status = 'PUBLISHED';
CREATE INDEX ix_podcast_episodes_community ON podcast_episodes (community_id, status);

CREATE TABLE podcast_chapters (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    episode_id     BIGINT       NOT NULL REFERENCES podcast_episodes (id) ON DELETE CASCADE,
    start_seconds  INTEGER      NOT NULL CHECK (start_seconds >= 0),
    title          VARCHAR(200) NOT NULL
);
CREATE INDEX ix_podcast_chapters_episode ON podcast_chapters (episode_id, start_seconds);

CREATE TABLE podcast_transcript_segments (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    episode_id     BIGINT        NOT NULL REFERENCES podcast_episodes (id) ON DELETE CASCADE,
    start_seconds  NUMERIC(9, 3) NOT NULL,
    end_seconds    NUMERIC(9, 3) NOT NULL,
    speaker        VARCHAR(80),
    text           TEXT          NOT NULL
);
CREATE INDEX ix_podcast_segments_episode ON podcast_transcript_segments (episode_id, start_seconds);

CREATE TABLE podcast_sources (
    episode_id  BIGINT  NOT NULL REFERENCES podcast_episodes (id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    post_id     BIGINT  NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    comment_id  BIGINT REFERENCES comments (id) ON DELETE CASCADE,
    PRIMARY KEY (episode_id, position)
);

-- Per-user listening state: resume position, completion and history in one row.
CREATE TABLE podcast_progress (
    user_id           BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    episode_id        BIGINT      NOT NULL REFERENCES podcast_episodes (id) ON DELETE CASCADE,
    position_seconds  INTEGER     NOT NULL DEFAULT 0,
    completed         BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, episode_id)
);
CREATE INDEX ix_podcast_progress_user ON podcast_progress (user_id, updated_at DESC);

CREATE TABLE podcast_saves (
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    episode_id  BIGINT      NOT NULL REFERENCES podcast_episodes (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, episode_id)
);

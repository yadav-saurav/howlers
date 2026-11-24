-- Phase 3 topic reputation v1 and Phase 9 moderator recognition (see docs/DECISIONS.md D-020).
-- Everything here is DERIVED from real rows (post_votes, comment_votes, reports, moderation_actions, appeals) except
-- the two things people actually choose: peer endorsements and the ranking opt-out.

-- ---------------------------------------------------------------- topic reputation
-- Totals per (user, topic). Rebuildable at any time from the vote tables; never edited by hand.
CREATE TABLE topic_reputation (
    user_id            BIGINT           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    topic_id           BIGINT           NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    points             DOUBLE PRECISION NOT NULL DEFAULT 0,  -- quality-weighted (what is shown)
    base_points        DOUBLE PRECISION NOT NULL DEFAULT 0,  -- unweighted pass; the input to voter weights
    upvotes_counted    INTEGER          NOT NULL DEFAULT 0,
    downvotes_counted  INTEGER          NOT NULL DEFAULT 0,
    votes_capped       INTEGER          NOT NULL DEFAULT 0,  -- votes ignored or trimmed by anti-farming caps
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, topic_id)
);
CREATE INDEX ix_topic_reputation_leaders ON topic_reputation (topic_id, points DESC, user_id);

-- Work queue: a vote (or a removal/restore) marks the content author dirty inside the vote transaction (one tiny
-- upsert); a background job recomputes dirty authors from the vote rows a few seconds later.
CREATE TABLE reputation_dirty (
    user_id    BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    queued_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per recompute, so the UI can say honestly when totals were last rebuilt.
CREATE TABLE reputation_runs (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind         VARCHAR(12) NOT NULL CHECK (kind IN ('FULL', 'INCREMENTAL')),
    authors      INTEGER     NOT NULL,
    rows_written INTEGER     NOT NULL,
    finished_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- moderator recognition
CREATE TABLE moderator_settings (
    user_id         BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    public_ranking  BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A co-moderator's "+1 helpful" for one period (calendar month, UTC), with an optional note.
CREATE TABLE moderator_endorsements (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id  BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    period        VARCHAR(7)   NOT NULL CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    endorser_id   BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    moderator_id  BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    note          VARCHAR(280),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (community_id, period, endorser_id, moderator_id),
    CHECK (endorser_id <> moderator_id)
);
CREATE INDEX ix_moderator_endorsements_target ON moderator_endorsements (moderator_id, period);

-- Computed per (community, moderator, period). Raw inputs are stored next to the components so the page can show
-- the whole formula. There is deliberately no column that ranks by removal volume.
CREATE TABLE moderator_scores (
    community_id               BIGINT           NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    moderator_id               BIGINT           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    period                     VARCHAR(7)       NOT NULL,
    decisions                  INTEGER          NOT NULL,  -- removals and bans made in the period
    decisions_reversed         INTEGER          NOT NULL,  -- of those, overturned on appeal or by another moderator
    reports_handled            INTEGER          NOT NULL,
    median_response_minutes    DOUBLE PRECISION,
    appeals_upheld             INTEGER          NOT NULL,
    appeals_overturned         INTEGER          NOT NULL,
    endorsements               INTEGER          NOT NULL,
    co_moderators              INTEGER          NOT NULL,
    community_reports          INTEGER          NOT NULL,
    community_reports_resolved INTEGER          NOT NULL,
    removal_rate               DOUBLE PRECISION,
    prev_removal_rate          DOUBLE PRECISION,
    consistency                DOUBLE PRECISION,
    responsiveness             DOUBLE PRECISION,
    appeal_quality             DOUBLE PRECISION,
    health                     DOUBLE PRECISION,
    peer                       DOUBLE PRECISION,
    score                      DOUBLE PRECISION NOT NULL,
    activity                   INTEGER          NOT NULL,  -- decisions + reports handled; gates ranking only
    badges                     TEXT[]           NOT NULL DEFAULT '{}',
    computed_at                TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (community_id, moderator_id, period)
);
CREATE INDEX ix_moderator_scores_period ON moderator_scores (period, score DESC);
CREATE INDEX ix_moderator_scores_moderator ON moderator_scores (moderator_id, period DESC);

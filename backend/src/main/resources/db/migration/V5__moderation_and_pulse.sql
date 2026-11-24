-- Phase 3 moderation (reports, auditable actions, bans, appeals, blocks) and Phase 4 Community Pulse.

CREATE TABLE reports (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id   BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    reporter_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type    VARCHAR(8)   NOT NULL CHECK (target_type IN ('POST', 'COMMENT')),
    post_id        BIGINT       NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    comment_id     BIGINT REFERENCES comments (id) ON DELETE CASCADE,
    reason         VARCHAR(100) NOT NULL,
    details        VARCHAR(1000) NOT NULL DEFAULT '',
    status         VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution     VARCHAR(12),                 -- REMOVED | APPROVED
    resolved_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((target_type = 'POST' AND comment_id IS NULL) OR (target_type = 'COMMENT' AND comment_id IS NOT NULL))
);
-- One open report per reporter per target.
CREATE UNIQUE INDEX ux_reports_open ON reports (reporter_id, post_id, coalesce(comment_id, 0)) WHERE status = 'OPEN';
CREATE INDEX ix_reports_queue ON reports (community_id, status, created_at DESC);
CREATE INDEX ix_reports_target ON reports (post_id, comment_id) WHERE status = 'OPEN';

-- Append-only audit log. Reversal is recorded on the row, never by deleting it.
CREATE TABLE moderation_actions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id    BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    moderator_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    action          VARCHAR(20)  NOT NULL,
    target_user_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    post_id         BIGINT REFERENCES posts (id) ON DELETE SET NULL,
    comment_id      BIGINT REFERENCES comments (id) ON DELETE SET NULL,
    reason          VARCHAR(300),
    expires_at      TIMESTAMPTZ,
    reversed_at     TIMESTAMPTZ,
    reversed_by     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_mod_actions_community ON moderation_actions (community_id, id DESC);
CREATE INDEX ix_mod_actions_target_user ON moderation_actions (target_user_id, id DESC);

CREATE TABLE community_bans (
    community_id  BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason        VARCHAR(300) NOT NULL,
    expires_at    TIMESTAMPTZ,                -- NULL = permanent
    banned_by     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    action_id     BIGINT REFERENCES moderation_actions (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (community_id, user_id)
);

CREATE TABLE appeals (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action_id    BIGINT        NOT NULL UNIQUE REFERENCES moderation_actions (id) ON DELETE CASCADE,
    user_id      BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message      VARCHAR(2000) NOT NULL,
    status       VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    response     VARCHAR(1000),
    resolved_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolved_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE user_blocks (
    blocker_id  BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id  BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);
CREATE INDEX ix_user_blocks_blocked ON user_blocks (blocked_id);

-- ---------------------------------------------------------------- Community Pulse
CREATE TABLE pulses (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id        BIGINT        NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    author_id           BIGINT REFERENCES users (id) ON DELETE SET NULL,
    type                VARCHAR(12)   NOT NULL CHECK (type IN ('POLL', 'PREDICTION', 'OPINION', 'DEBATE')),
    title               VARCHAR(200)  NOT NULL,
    description         VARCHAR(1000) NOT NULL DEFAULT '',
    multiple            BOOLEAN       NOT NULL DEFAULT FALSE,
    anonymous           BOOLEAN       NOT NULL DEFAULT FALSE,
    allow_change        BOOLEAN       NOT NULL DEFAULT TRUE,
    closes_at           TIMESTAMPTZ   NOT NULL,
    closed_early_at     TIMESTAMPTZ,
    resolved_option_id  BIGINT,
    total_voters        INTEGER       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_pulses_community ON pulses (community_id, closes_at);

CREATE TABLE pulse_options (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pulse_id  BIGINT       NOT NULL REFERENCES pulses (id) ON DELETE CASCADE,
    position  INTEGER      NOT NULL,
    label     VARCHAR(120) NOT NULL,
    votes     INTEGER      NOT NULL DEFAULT 0
);
CREATE INDEX ix_pulse_options_pulse ON pulse_options (pulse_id, position);
ALTER TABLE pulses ADD CONSTRAINT fk_pulses_resolved_option FOREIGN KEY (resolved_option_id) REFERENCES pulse_options (id) ON DELETE SET NULL;

CREATE TABLE pulse_votes (
    pulse_id   BIGINT      NOT NULL REFERENCES pulses (id) ON DELETE CASCADE,
    option_id  BIGINT      NOT NULL REFERENCES pulse_options (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (option_id, user_id)
);
CREATE INDEX ix_pulse_votes_pulse_user ON pulse_votes (pulse_id, user_id);

CREATE TABLE pulse_reasons (
    pulse_id    BIGINT       NOT NULL REFERENCES pulses (id) ON DELETE CASCADE,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    text        VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (pulse_id, user_id)
);

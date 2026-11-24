-- Pack rooms (group conversations), emoji reactions and per-community automod. See docs/DECISIONS.md D-043 to D-045.
-- Additive only: new columns default to the existing one-to-one behaviour, and every new table is independent.

-- ---------------------------------------------------------------- pack rooms (D-043)
-- A conversation is either DIRECT (exactly two people, keyed by the sorted pair) or a ROOM (3 to 50 members at
-- creation, a name and an optional icon emoji, no pair key).
ALTER TABLE dm_conversations ALTER COLUMN pair_key DROP NOT NULL;
ALTER TABLE dm_conversations ADD COLUMN kind VARCHAR(6) NOT NULL DEFAULT 'DIRECT' CHECK (kind IN ('DIRECT', 'ROOM'));
ALTER TABLE dm_conversations ADD COLUMN name VARCHAR(60);
ALTER TABLE dm_conversations ADD COLUMN icon_emoji VARCHAR(16);
ALTER TABLE dm_conversations ADD COLUMN created_by BIGINT REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE dm_conversations ADD CONSTRAINT ck_dm_conversation_shape
    CHECK ((kind = 'DIRECT' AND pair_key IS NOT NULL) OR (kind = 'ROOM' AND pair_key IS NULL AND name IS NOT NULL));

-- Room roles. One-to-one participants stay MEMBER; nothing reads the role of a DIRECT conversation.
ALTER TABLE dm_participants ADD COLUMN role VARCHAR(6) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'));
CREATE UNIQUE INDEX ux_dm_participants_one_owner ON dm_participants (conversation_id) WHERE role = 'OWNER';

-- System lines ("mira_k added dev_ostrander") live in the same timeline as messages, so ordering and paging stay simple.
ALTER TABLE dm_messages ADD COLUMN kind VARCHAR(6) NOT NULL DEFAULT 'USER' CHECK (kind IN ('USER', 'SYSTEM'));
ALTER TABLE dm_messages ADD COLUMN system_event VARCHAR(16);
ALTER TABLE dm_messages ADD COLUMN system_target_id BIGINT REFERENCES users (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------- reactions (D-044)
-- One row per (target, person, reaction kind). Targets are polymorphic: every read re-checks the target's visibility,
-- so a reaction on content that was later removed or made private is simply never shown.
CREATE TABLE reactions (
    target_type  VARCHAR(8)  NOT NULL CHECK (target_type IN ('POST', 'COMMENT', 'MESSAGE')),
    target_id    BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reaction     VARCHAR(12) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (target_type, target_id, user_id, reaction)
);
CREATE INDEX ix_reactions_target ON reactions (target_type, target_id, reaction, created_at);
CREATE INDEX ix_reactions_user ON reactions (user_id);

-- ---------------------------------------------------------------- automod (D-045)
CREATE TABLE automod_rules (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id  BIGINT        NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    name          VARCHAR(80)   NOT NULL,
    kind          VARCHAR(12)   NOT NULL CHECK (kind IN ('KEYWORD', 'REGEX', 'LINK_LIMIT', 'ACCOUNT_AGE', 'KARMA', 'DOMAIN')),
    -- KEYWORD: one phrase per line (* = any run of letters); REGEX: one RE2 pattern; DOMAIN: one domain per line.
    pattern       VARCHAR(2000),
    -- LINK_LIMIT: most links allowed; ACCOUNT_AGE: minimum days; KARMA: minimum post + comment karma.
    threshold     INTEGER,
    applies_to    VARCHAR(8)    NOT NULL DEFAULT 'BOTH' CHECK (applies_to IN ('POSTS', 'COMMENTS', 'BOTH')),
    action        VARCHAR(6)    NOT NULL CHECK (action IN ('HOLD', 'REMOVE', 'FLAG')),
    -- Shown to the author when the rule holds or removes their content.
    reason        VARCHAR(200)  NOT NULL,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_automod_rules_community ON automod_rules (community_id, id);

-- Every time a rule matched at write time (the moderation log gets a matching AUTOMOD_* / REMOVE_* row too).
CREATE TABLE automod_hits (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id  BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    rule_id       BIGINT REFERENCES automod_rules (id) ON DELETE SET NULL,
    rule_name     VARCHAR(80)  NOT NULL,
    action        VARCHAR(6)   NOT NULL CHECK (action IN ('HOLD', 'REMOVE', 'FLAG')),
    -- What happened to the content (the strongest matching action wins; a spam hold counts as HOLD).
    outcome       VARCHAR(6)   NOT NULL CHECK (outcome IN ('HOLD', 'REMOVE', 'FLAG')),
    target_type   VARCHAR(8)   NOT NULL CHECK (target_type IN ('POST', 'COMMENT')),
    post_id       BIGINT REFERENCES posts (id) ON DELETE CASCADE,
    comment_id    BIGINT REFERENCES comments (id) ON DELETE CASCADE,
    author_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    detail        VARCHAR(300) NOT NULL DEFAULT '',
    excerpt       VARCHAR(300) NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_automod_hits_community ON automod_hits (community_id, id DESC);

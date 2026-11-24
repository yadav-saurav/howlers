-- D-036: podcast audio generated from scripts with a text-to-speech provider (ElevenLabs).

-- Where an episode's current media came from. Uploaded media stays UPLOADED; generated audio is AI_TTS and carries
-- the provider and model so the "AI-generated voice" label can name them.
ALTER TABLE podcast_episodes
    ADD COLUMN audio_origin   VARCHAR(8) NOT NULL DEFAULT 'UPLOADED' CHECK (audio_origin IN ('UPLOADED', 'AI_TTS')),
    ADD COLUMN audio_provider VARCHAR(40),
    ADD COLUMN audio_model    VARCHAR(80);

-- One row per generation attempt. Characters are what the provider bills for; the daily budget sums them per community.
CREATE TABLE podcast_tts_jobs (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    episode_id        BIGINT       NOT NULL REFERENCES podcast_episodes (id) ON DELETE CASCADE,
    community_id      BIGINT       NOT NULL REFERENCES communities (id) ON DELETE CASCADE,
    requested_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    status            VARCHAR(10)  NOT NULL DEFAULT 'QUEUED'
                      CHECK (status IN ('QUEUED', 'GENERATING', 'READY', 'FAILED', 'CANCELLED')),
    provider          VARCHAR(40)  NOT NULL,
    model             VARCHAR(80)  NOT NULL,
    voice_a           VARCHAR(80)  NOT NULL,
    voice_b           VARCHAR(80)  NOT NULL,
    characters        INTEGER      NOT NULL CHECK (characters > 0),
    characters_sent   INTEGER      NOT NULL DEFAULT 0,
    chunks_total      INTEGER      NOT NULL CHECK (chunks_total > 0),
    chunks_done       INTEGER      NOT NULL DEFAULT 0,
    cancel_requested  BOOLEAN      NOT NULL DEFAULT FALSE,
    duration_seconds  NUMERIC(9, 3),
    error             VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ
);
-- At most one active generation per episode.
CREATE UNIQUE INDEX ux_podcast_tts_jobs_active ON podcast_tts_jobs (episode_id) WHERE status IN ('QUEUED', 'GENERATING');
CREATE INDEX ix_podcast_tts_jobs_episode ON podcast_tts_jobs (episode_id, id DESC);
CREATE INDEX ix_podcast_tts_jobs_budget ON podcast_tts_jobs (community_id, created_at);

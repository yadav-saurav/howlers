-- Privacy and safety hardening (D-028 to D-030).

-- Which engine checked a private file (D-029). STRUCTURAL = type and structure checks only (not an antivirus scan);
-- CLAMAV = those checks plus a clamd INSTREAM scan that found nothing. Existing rows were checked structurally.
ALTER TABLE user_files
    ADD COLUMN scan_engine VARCHAR(12) NOT NULL DEFAULT 'STRUCTURAL'
        CHECK (scan_engine IN ('STRUCTURAL', 'CLAMAV'));

-- One row per run of a one-time maintenance job (D-030), e.g. moving legacy DM images to the private prefix.
-- Jobs are idempotent; the table records what each run did and who started it (NULL = at startup).
CREATE TABLE maintenance_runs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job           VARCHAR(60)  NOT NULL,
    triggered_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    candidates    INTEGER      NOT NULL DEFAULT 0,
    moved         INTEGER      NOT NULL DEFAULT 0,
    skipped       INTEGER      NOT NULL DEFAULT 0,
    failed        INTEGER      NOT NULL DEFAULT 0,
    detail        VARCHAR(500)
);
CREATE INDEX ix_maintenance_runs_job ON maintenance_runs (job, id DESC);

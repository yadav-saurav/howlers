-- Chat upgrade: replies, voice notes, per-person favourites and shared conversation themes.
-- Additive only: every new column is nullable or defaults to the existing behaviour.

-- A reply points at an earlier message of the same conversation (checked by the application). Messages are normally
-- soft-deleted, so the preview can say "deleted"; the link only goes away when the row itself does.
ALTER TABLE dm_messages ADD COLUMN reply_to_id BIGINT REFERENCES dm_messages (id) ON DELETE SET NULL;
CREATE INDEX ix_dm_messages_reply_to ON dm_messages (reply_to_id) WHERE reply_to_id IS NOT NULL;

-- Each participant has their own favourites.
ALTER TABLE dm_participants ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;

-- One theme for everyone in the conversation; NULL is the default look.
ALTER TABLE dm_conversations ADD COLUMN theme VARCHAR(24);

-- Length of a voice note, as reported by the recording client.
ALTER TABLE media_assets ADD COLUMN duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms > 0);

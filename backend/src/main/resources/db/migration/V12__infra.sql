-- Phase 10 infrastructure (D-023, D-024).

-- Resized image variants (320 and 960 px wide) stored beside the original as <key>_w320.<ext> / <key>_w960.<ext>.
-- Comma-separated widths that exist for this asset ("320,960"); NULL for assets uploaded before V12, GIFs, WebP and
-- images narrower than 320 px. The original always exists, so NULL is always safe to render.
ALTER TABLE media_assets ADD COLUMN variant_widths VARCHAR(40);

-- Direct-message images uploaded through POST /me/dm-media live under the private/ storage prefix and are served only
-- to conversation participants through signed grants (D-024). The grant check looks messages up by media id.
CREATE INDEX IF NOT EXISTS ix_dm_message_media_media ON dm_message_media (media_id);

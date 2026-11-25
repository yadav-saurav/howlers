-- Topic reputation also counts taking part: +2 per post and +1 per comment in the topic (on top of votes).
ALTER TABLE topic_reputation
    ADD COLUMN posts_counted    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN comments_counted INTEGER NOT NULL DEFAULT 0;

-- Communities without a topic count towards General, so every public post lands on some topic board.
INSERT INTO topics (slug, name, description)
VALUES ('general', 'General', 'Everything that does not fit another topic')
ON CONFLICT (slug) DO NOTHING;

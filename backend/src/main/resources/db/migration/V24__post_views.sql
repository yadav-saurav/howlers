-- Post views, like Reddit's: each signed-in member counts once per post (the author never counts), and only the
-- author sees the number.
ALTER TABLE posts ADD COLUMN view_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE post_views (
    post_id    BIGINT      NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    viewed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

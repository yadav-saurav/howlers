-- Top now sorts by votes + comments + views.
DROP INDEX IF EXISTS idx_posts_engagement;
CREATE INDEX IF NOT EXISTS idx_posts_top ON posts ((score + comment_count + view_count) DESC, id DESC) WHERE deleted_at IS NULL;

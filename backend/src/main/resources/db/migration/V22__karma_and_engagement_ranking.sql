-- Karma now also rewards taking part: +2 for each live post and +1 for each live comment (on top of votes).
UPDATE users u SET post_karma = u.post_karma + 2 * x.n
FROM (SELECT author_id, count(*) AS n FROM posts
      WHERE author_id IS NOT NULL AND deleted_at IS NULL AND removed = FALSE AND pending = FALSE
      GROUP BY author_id) x
WHERE u.id = x.author_id;

UPDATE users u SET comment_karma = u.comment_karma + x.n
FROM (SELECT author_id, count(*) AS n FROM comments
      WHERE author_id IS NOT NULL AND deleted_at IS NULL AND removed = FALSE AND pending = FALSE
      GROUP BY author_id) x
WHERE u.id = x.author_id;

-- Hot now treats every comment like an upvote (ScoreMath.hot): recompute stored ranks with the same maths.
-- 1.6423744 is z squared for z = 1.281551565545 (the Wilson bound used in ScoreMath).
UPDATE posts p SET hot_rank =
    sign(p.upvotes - p.downvotes + p.comment_count) * log(greatest(abs(p.upvotes - p.downvotes + p.comment_count), 1)::numeric)::float8
  + 1.5 * CASE WHEN p.upvotes + p.downvotes = 0 THEN 0 ELSE
      ((p.upvotes::float8 / (p.upvotes + p.downvotes)) + 1.6423744 / (2 * (p.upvotes + p.downvotes))
        - 1.281551565545 * sqrt(((p.upvotes::float8 / (p.upvotes + p.downvotes)) * (1 - p.upvotes::float8 / (p.upvotes + p.downvotes))
            + 1.6423744 / (4 * (p.upvotes + p.downvotes))) / (p.upvotes + p.downvotes)))
      / (1 + 1.6423744 / (p.upvotes + p.downvotes)) END
  + (extract(epoch FROM p.created_at)::float8 - 1700000000) / 50400.0;

-- Top sorts by votes plus comments.
CREATE INDEX IF NOT EXISTS idx_posts_engagement ON posts ((score + comment_count) DESC, id DESC) WHERE deleted_at IS NULL;

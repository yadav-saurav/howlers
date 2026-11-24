package app.howlers.vote;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityRepository;
import app.howlers.notification.NotificationService;
import app.howlers.post.dto.PostDtos.VoteResult;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrency-safe voting.
 *
 * <ol>
 *   <li>A transaction-scoped advisory lock on (target, voter) serialises one user's concurrent clicks
 *       on the same target, so "read old vote → write new vote" can't interleave.</li>
 *   <li>Counters move by <em>relative</em> deltas in a single UPDATE (row-locked by PostgreSQL), so
 *       different users voting at the same time never lose each other's updates.</li>
 *   <li>The (target, user) primary key on the vote table makes duplicate votes impossible.</li>
 *   <li>Rank columns are recomputed from the post-update counters inside the same row lock.</li>
 * </ol>
 * Self-votes are allowed for display but never change the author's karma.
 */
@Service
public class VoteService {
    private static final int POST_NS = 0x504F5354;    // "POST"
    private static final int COMMENT_NS = 0x434D4E54; // "CMNT"

    private final JdbcClient jdbc;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final RateLimiter limiter;
    private final NotificationService notifications;
    private final app.howlers.realtime.LiveCounters counters;
    private final app.howlers.reputation.ReputationQueue reputation;
    private final app.howlers.common.metrics.HowlersMetrics metrics;

    public VoteService(JdbcClient jdbc, CommunityRepository communities, CommunityAccess access, RateLimiter limiter,
                       NotificationService notifications, app.howlers.realtime.LiveCounters counters,
                       app.howlers.reputation.ReputationQueue reputation, app.howlers.common.metrics.HowlersMetrics metrics) {
        this.metrics = metrics;
        this.counters = counters;
        this.reputation = reputation;
        this.jdbc = jdbc;
        this.communities = communities;
        this.access = access;
        this.limiter = limiter;
        this.notifications = notifications;
    }

    private record Target(Long authorId, long communityId, boolean live, String title) {}

    @Transactional
    public VoteResult votePost(long postId, AuthUser user, int value) {
        limiter.check("vote:" + user.id(), 120, Duration.ofMinutes(1));
        Target t = jdbc.sql("SELECT author_id, community_id, deleted_at IS NULL AND removed = FALSE, title FROM posts WHERE id = ?")
                .param(postId).query((rs, i) -> new Target((Long) rs.getObject(1), rs.getLong(2), rs.getBoolean(3), rs.getString(4)))
                .optional().orElseThrow(() -> ApiException.notFound("Post"));
        requireCanVote(t, user);
        lock(POST_NS ^ Long.hashCode(postId), Long.hashCode(user.id()));

        int old = jdbc.sql("SELECT value FROM post_votes WHERE post_id = ? AND user_id = ?").params(postId, user.id())
                .query(Integer.class).optional().orElse(0);
        if (old != value) {
            writeVote("post_votes", "post_id", postId, user.id(), old, value);
            metrics.vote("post", value);
        }

        int du = (value == 1 ? 1 : 0) - (old == 1 ? 1 : 0);
        int dd = (value == -1 ? 1 : 0) - (old == -1 ? 1 : 0);
        record Counts(int up, int down, int score, int comments, Instant created) {}
        Counts c = jdbc.sql("""
                        UPDATE posts SET upvotes = upvotes + :du, downvotes = downvotes + :dd, score = score + :ds
                        WHERE id = :id RETURNING upvotes, downvotes, score, comment_count, created_at""")
                .param("du", du).param("dd", dd).param("ds", du - dd).param("id", postId)
                .query((rs, i) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getObject(5, OffsetDateTime.class).toInstant()))
                .single();
        jdbc.sql("UPDATE posts SET hot_rank = ? WHERE id = ?")
                .params(ScoreMath.hot(c.up(), c.down(), c.comments(), c.created()), postId).update();

        if (t.authorId() != null && t.authorId() != user.id() && old != value) {
            jdbc.sql("UPDATE users SET post_karma = post_karma + ? WHERE id = ?").params(value - old, t.authorId()).update();
            reputation.markDirty(t.authorId()); // topic reputation is recomputed off the request path
            int milestone = ScoreMath.crossedMilestone(c.score() - (du - dd), c.score());
            if (milestone > 0) notifications.postMilestone(t.authorId(), postId, t.title(), milestone);
        }
        counters.postChanged(postId);
        return new VoteResult(c.score(), c.up(), c.down(), value);
    }

    @Transactional
    public VoteResult voteComment(long commentId, AuthUser user, int value) {
        limiter.check("vote:" + user.id(), 120, Duration.ofMinutes(1));
        Target t = jdbc.sql("""
                        SELECT c.author_id, p.community_id,
                               c.deleted_at IS NULL AND c.removed = FALSE AND p.deleted_at IS NULL, ''
                        FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.id = ?""")
                .param(commentId).query((rs, i) -> new Target((Long) rs.getObject(1), rs.getLong(2), rs.getBoolean(3), rs.getString(4)))
                .optional().orElseThrow(() -> ApiException.notFound("Comment"));
        requireCanVote(t, user);
        lock(COMMENT_NS ^ Long.hashCode(commentId), Long.hashCode(user.id()));

        int old = jdbc.sql("SELECT value FROM comment_votes WHERE comment_id = ? AND user_id = ?").params(commentId, user.id())
                .query(Integer.class).optional().orElse(0);
        if (old != value) {
            writeVote("comment_votes", "comment_id", commentId, user.id(), old, value);
            metrics.vote("comment", value);
        }

        int du = (value == 1 ? 1 : 0) - (old == 1 ? 1 : 0);
        int dd = (value == -1 ? 1 : 0) - (old == -1 ? 1 : 0);
        record Counts(int up, int down, int score) {}
        Counts c = jdbc.sql("""
                        UPDATE comments SET upvotes = upvotes + :du, downvotes = downvotes + :dd, score = score + :ds
                        WHERE id = :id RETURNING upvotes, downvotes, score""")
                .param("du", du).param("dd", dd).param("ds", du - dd).param("id", commentId)
                .query((rs, i) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3))).single();
        jdbc.sql("UPDATE comments SET best_rank = ?, controversy = ? WHERE id = ?")
                .params(ScoreMath.wilson(c.up(), c.down()), ScoreMath.controversy(c.up(), c.down()), commentId).update();

        if (t.authorId() != null && t.authorId() != user.id() && old != value) {
            jdbc.sql("UPDATE users SET comment_karma = comment_karma + ? WHERE id = ?").params(value - old, t.authorId()).update();
            reputation.markDirty(t.authorId());
        }
        long postId = jdbc.sql("SELECT post_id FROM comments WHERE id = ?").param(commentId).query(Long.class).single();
        counters.commentChanged(postId, commentId);
        return new VoteResult(c.score(), c.up(), c.down(), value);
    }

    private void requireCanVote(Target t, AuthUser user) {
        if (!t.live()) throw ApiException.badRequest("You can't vote on deleted or removed content");
        Community community = communities.findById(t.communityId()).orElseThrow(() -> ApiException.notFound("Community"));
        Optional<app.howlers.community.CommunityRole> role = access.roleOf(community, user);
        if (!access.canView(community, role, user)) throw ApiException.forbidden("This community is private");
        access.requireNotBanned(community.getId(), user);
    }

    private void lock(int k1, int k2) {
        jdbc.sql("SELECT pg_advisory_xact_lock(?, ?)").params(k1, k2).query(Object.class).list();
    }

    private void writeVote(String table, String col, long targetId, long userId, int old, int value) {
        if (value == 0) {
            jdbc.sql("DELETE FROM " + table + " WHERE " + col + " = ? AND user_id = ?").params(targetId, userId).update();
        } else if (old == 0) {
            jdbc.sql("INSERT INTO " + table + " (" + col + ", user_id, value) VALUES (?, ?, ?)").params(targetId, userId, value).update();
        } else {
            jdbc.sql("UPDATE " + table + " SET value = ?, updated_at = now() WHERE " + col + " = ? AND user_id = ?")
                    .params(value, targetId, userId).update();
        }
    }
}

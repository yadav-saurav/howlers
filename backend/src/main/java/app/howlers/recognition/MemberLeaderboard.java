package app.howlers.recognition;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityMapper;
import app.howlers.community.CommunityRepository;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "Members" leaderboard: who took part most in a month, computed live from posts, comments and upvotes, so it
 * fills in from the first post on a new site (the moderator and topic boards need moderation activity or votes from
 * others first).
 *
 * <pre>
 *   points = 2 × posts + 1 × comments + 1 × upvotes from other people on their posts and comments
 * </pre>
 *
 * Only live content counts (not deleted, removed or held for review), in public communities (or the one community
 * asked for, if the viewer can see it). Self-votes never count. Deleted and suspended accounts and members whose
 * profile is private are left out.
 */
@Service
public class MemberLeaderboard {
    /** Same weights as karma: 2 a post, 1 a comment, 1 per upvote from someone else. */
    public static final int POST_POINTS = 2;
    public static final int COMMENT_POINTS = 1;
    public static final int UPVOTE_POINTS = 1;
    static final int MONTHS_LISTED = 6;

    public record MemberEntry(int rank, UserSummary user, int posts, int comments, int upvotes, int points) {}

    public record MemberBoard(String period, List<String> periods, CommunityRef community, List<MemberEntry> entries,
                              String formula) {}

    private record Row(long userId, int posts, int comments, int upvotes) {
        int points() {
            return POST_POINTS * posts + COMMENT_POINTS * comments + UPVOTE_POINTS * upvotes;
        }
    }

    private final JdbcClient jdbc;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final UserDirectory users;

    public MemberLeaderboard(JdbcClient jdbc, CommunityRepository communities, CommunityAccess access, UserDirectory users) {
        this.jdbc = jdbc;
        this.communities = communities;
        this.access = access;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public MemberBoard board(String periodParam, String slug, int limit, AuthUser viewer) {
        YearMonth period = RecognitionService.parsePeriod(periodParam);
        Community community = null;
        if (slug != null && !slug.isBlank()) {
            community = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
            access.requireView(community, viewer);
        }
        OffsetDateTime start = period.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = period.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<Row> rows = jdbc.sql("""
                        WITH scope AS (
                          SELECT c.id FROM communities c
                          WHERE c.status <> 'BANNED' AND ((:cid = 0 AND c.visibility <> 'PRIVATE') OR c.id = :cid)
                        ),
                        live_posts AS (
                          SELECT p.id, p.author_id, p.created_at FROM posts p
                          WHERE p.community_id IN (SELECT id FROM scope)
                            AND p.deleted_at IS NULL AND p.removed = FALSE AND p.pending = FALSE
                        ),
                        p AS (
                          SELECT author_id AS uid, count(*) AS n FROM live_posts
                          WHERE author_id IS NOT NULL AND created_at >= :start AND created_at < :end GROUP BY 1
                        ),
                        cm AS (
                          SELECT c.author_id AS uid, count(*) AS n
                          FROM comments c JOIN live_posts lp ON lp.id = c.post_id
                          WHERE c.author_id IS NOT NULL AND c.deleted_at IS NULL AND c.removed = FALSE AND c.pending = FALSE
                            AND c.created_at >= :start AND c.created_at < :end
                          GROUP BY 1
                        ),
                        votes AS (
                          SELECT lp.author_id AS uid FROM post_votes v JOIN live_posts lp ON lp.id = v.post_id
                          WHERE v.value = 1 AND v.user_id <> lp.author_id AND v.created_at >= :start AND v.created_at < :end
                          UNION ALL
                          SELECT c.author_id FROM comment_votes v
                          JOIN comments c ON c.id = v.comment_id JOIN live_posts lp ON lp.id = c.post_id
                          WHERE v.value = 1 AND v.user_id <> c.author_id AND c.deleted_at IS NULL AND c.removed = FALSE
                            AND v.created_at >= :start AND v.created_at < :end
                        ),
                        uv AS (SELECT uid, count(*) AS n FROM votes WHERE uid IS NOT NULL GROUP BY uid),
                        ids AS (SELECT uid FROM p UNION SELECT uid FROM cm UNION SELECT uid FROM uv)
                        SELECT ids.uid, coalesce(p.n, 0) AS posts, coalesce(cm.n, 0) AS comments, coalesce(uv.n, 0) AS upvotes
                        FROM ids
                        JOIN users u ON u.id = ids.uid
                        LEFT JOIN p ON p.uid = ids.uid
                        LEFT JOIN cm ON cm.uid = ids.uid
                        LEFT JOIN uv ON uv.uid = ids.uid
                        WHERE u.deleted_at IS NULL AND u.suspended_at IS NULL AND u.profile_public
                        ORDER BY (:pp * coalesce(p.n, 0) + :cp * coalesce(cm.n, 0) + :up * coalesce(uv.n, 0)) DESC, ids.uid
                        LIMIT :lim""")
                .param("cid", community == null ? 0L : community.getId())
                .param("start", start).param("end", end)
                .param("pp", POST_POINTS).param("cp", COMMENT_POINTS).param("up", UPVOTE_POINTS)
                .param("lim", Math.max(1, Math.min(limit, 100)))
                .query((rs, i) -> new Row(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
                .list();

        Map<Long, UserSummary> people = users.summaries(rows.stream().map(Row::userId).toList());
        List<MemberEntry> entries = new ArrayList<>();
        for (Row r : rows) {
            UserSummary u = people.get(r.userId());
            if (u != null) entries.add(new MemberEntry(entries.size() + 1, u, r.posts(), r.comments(), r.upvotes(), r.points()));
        }
        return new MemberBoard(period.toString(), recentPeriods(), community == null ? null : CommunityMapper.ref(community),
                entries, POST_POINTS + " points a post, " + COMMENT_POINTS + " a comment and " + UPVOTE_POINTS
                        + " for each upvote from someone else");
    }

    /** The current month and the five before it, newest first. */
    static List<String> recentPeriods() {
        YearMonth now = RecognitionService.currentPeriod();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < MONTHS_LISTED; i++) out.add(now.minusMonths(i).toString());
        return out;
    }
}

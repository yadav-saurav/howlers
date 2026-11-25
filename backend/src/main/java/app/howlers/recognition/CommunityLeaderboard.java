package app.howlers.recognition;

import app.howlers.community.Community;
import app.howlers.community.CommunityMapper;
import app.howlers.community.CommunityRepository;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "Communities" leaderboard: which communities were most active in a month. A community's points are the sum of
 * the points its members earned there, with the same weights as the Members board and karma:
 *
 * <pre>
 *   points = 2 × posts + 1 × comments + 1 × upvotes from other people, inside the community
 * </pre>
 *
 * Computed live, so it fills in from the first post. Only public, non-banned communities and live content count
 * (not deleted, removed or held); self-votes never count; deleted and suspended accounts are left out.
 */
@Service
public class CommunityLeaderboard {

    public record CommunityEntry(int rank, CommunityRef community, int posts, int comments, int upvotes,
                                 int activeMembers, int points) {}

    public record CommunityBoard(String period, List<String> periods, List<CommunityEntry> entries, String formula) {}

    private record Row(long communityId, int posts, int comments, int upvotes, int members) {
        int points() {
            return MemberLeaderboard.POST_POINTS * posts + MemberLeaderboard.COMMENT_POINTS * comments
                    + MemberLeaderboard.UPVOTE_POINTS * upvotes;
        }
    }

    private final JdbcClient jdbc;
    private final CommunityRepository communities;

    public CommunityLeaderboard(JdbcClient jdbc, CommunityRepository communities) {
        this.jdbc = jdbc;
        this.communities = communities;
    }

    @Transactional(readOnly = true)
    public CommunityBoard board(String periodParam, int limit) {
        YearMonth period = RecognitionService.parsePeriod(periodParam);
        OffsetDateTime start = period.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = period.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<Row> rows = jdbc.sql("""
                        WITH scope AS (
                          SELECT c.id FROM communities c WHERE c.status <> 'BANNED' AND c.visibility <> 'PRIVATE'
                        ),
                        people AS (
                          SELECT id FROM users WHERE deleted_at IS NULL AND suspended_at IS NULL
                        ),
                        live_posts AS (
                          SELECT p.id, p.community_id, p.author_id, p.created_at FROM posts p
                          WHERE p.community_id IN (SELECT id FROM scope)
                            AND p.deleted_at IS NULL AND p.removed = FALSE AND p.pending = FALSE
                        ),
                        acts AS (
                          SELECT lp.community_id AS cid, lp.author_id AS uid, 'P' AS k FROM live_posts lp
                          WHERE lp.created_at >= :start AND lp.created_at < :end
                          UNION ALL
                          SELECT lp.community_id, c.author_id, 'C'
                          FROM comments c JOIN live_posts lp ON lp.id = c.post_id
                          WHERE c.deleted_at IS NULL AND c.removed = FALSE AND c.pending = FALSE
                            AND c.created_at >= :start AND c.created_at < :end
                          UNION ALL
                          SELECT lp.community_id, lp.author_id, 'V'
                          FROM post_votes v JOIN live_posts lp ON lp.id = v.post_id
                          WHERE v.value = 1 AND v.user_id <> lp.author_id AND v.created_at >= :start AND v.created_at < :end
                          UNION ALL
                          SELECT lp.community_id, c.author_id, 'V'
                          FROM comment_votes v JOIN comments c ON c.id = v.comment_id JOIN live_posts lp ON lp.id = c.post_id
                          WHERE v.value = 1 AND v.user_id <> c.author_id AND c.deleted_at IS NULL AND c.removed = FALSE
                            AND v.created_at >= :start AND v.created_at < :end
                        )
                        SELECT cid,
                               count(*) FILTER (WHERE k = 'P') AS posts,
                               count(*) FILTER (WHERE k = 'C') AS comments,
                               count(*) FILTER (WHERE k = 'V') AS upvotes,
                               count(DISTINCT uid) AS members
                        FROM acts WHERE uid IN (SELECT id FROM people)
                        GROUP BY cid
                        ORDER BY (:pp * count(*) FILTER (WHERE k = 'P') + :cp * count(*) FILTER (WHERE k = 'C')
                                  + :up * count(*) FILTER (WHERE k = 'V')) DESC, cid
                        LIMIT :lim""")
                .param("start", start).param("end", end)
                .param("pp", MemberLeaderboard.POST_POINTS).param("cp", MemberLeaderboard.COMMENT_POINTS)
                .param("up", MemberLeaderboard.UPVOTE_POINTS)
                .param("lim", Math.max(1, Math.min(limit, 100)))
                .query((rs, i) -> new Row(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
                .list();

        Map<Long, Community> byId = new HashMap<>();
        communities.findAllById(rows.stream().map(Row::communityId).toList()).forEach(c -> byId.put(c.getId(), c));
        List<CommunityEntry> entries = new ArrayList<>();
        for (Row r : rows) {
            Community c = byId.get(r.communityId());
            if (c != null) {
                entries.add(new CommunityEntry(entries.size() + 1, CommunityMapper.ref(c), r.posts(), r.comments(),
                        r.upvotes(), r.members(), r.points()));
            }
        }
        return new CommunityBoard(period.toString(), MemberLeaderboard.recentPeriods(), entries,
                "The points its members earn there: " + MemberLeaderboard.POST_POINTS + " a post, "
                        + MemberLeaderboard.COMMENT_POINTS + " a comment and " + MemberLeaderboard.UPVOTE_POINTS
                        + " for each upvote from someone else");
    }
}

package app.howlers.spam;

import app.howlers.comment.CommentService;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityRepository;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.notification.NotificationService;
import app.howlers.post.PostService;
import app.howlers.realtime.RealtimePublisher;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review of content held by the spam heuristics. Approving releases it (counters, notifications and live events
 * happen then); removing keeps it removed, records a normal, appealable moderator removal and tells the author.
 */
@Service
public class SpamService {
    private final JdbcClient jdbc;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final PostService posts;
    private final CommentService comments;
    private final UserDirectory directory;
    private final NotificationService notifications;
    private final RealtimePublisher realtime;

    public SpamService(JdbcClient jdbc, CommunityRepository communities, CommunityAccess access, PostService posts,
                       CommentService comments, UserDirectory directory, NotificationService notifications,
                       RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.communities = communities;
        this.access = access;
        this.posts = posts;
        this.comments = comments;
        this.directory = directory;
        this.notifications = notifications;
        this.realtime = realtime;
    }

    public record SpamFlagDto(long id, CommunityRef community, UserSummary author, Instant authorJoinedAt, String targetType,
                              Long postId, Long commentId, String postTitle, List<String> rules, String outcome,
                              String details, String excerpt, String status, UserSummary resolvedBy, Instant resolvedAt,
                              Instant createdAt) {}

    private record Row(long id, long communityId, String slug, String name, String icon, Long authorId, Instant authorJoined,
                       String type, Long postId, Long commentId, String postTitle, String rules, String outcome,
                       String details, String excerpt, String status, Long resolvedBy, Instant resolvedAt, Instant createdAt) {}

    private static final String SELECT = """
            SELECT f.id, f.community_id, c.slug, c.name, c.icon_url, f.author_id, u.created_at AS joined, f.target_type,
                   f.post_id, f.comment_id, p.title, f.rules, f.outcome, f.details, f.excerpt, f.status, f.resolved_by,
                   f.resolved_at, f.created_at
            FROM spam_flags f JOIN communities c ON c.id = f.community_id
            LEFT JOIN users u ON u.id = f.author_id LEFT JOIN posts p ON p.id = f.post_id
            """;

    // ------------------------------------------------------------------ reads

    /** Open holds for one community's mod queue (moderators only). */
    @Transactional(readOnly = true)
    public List<SpamFlagDto> communityHolds(String slug, AuthUser user) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        return toDtos(jdbc.sql(SELECT + " WHERE f.community_id = ? AND f.status = 'OPEN' ORDER BY f.id DESC LIMIT 200")
                .param(c.getId()).query(this::row).list());
    }

    public long openHoldCount(long communityId) {
        return jdbc.sql("SELECT count(*) FROM spam_flags WHERE community_id = ? AND status = 'OPEN'").param(communityId)
                .query(Long.class).single();
    }

    /** Site-wide flags for the admin dashboard; {@code openOnly=false} includes rejections and resolved flags. */
    @Transactional(readOnly = true)
    public CursorPage<SpamFlagDto> siteFlags(boolean openOnly, String cursor, int limit) {
        Cursors.Key key = Cursors.decode(cursor);
        List<Row> rows = jdbc.sql(SELECT + """
                         WHERE (:open = FALSE OR f.status = 'OPEN') AND (:hasCursor = FALSE OR f.id < :cid)
                        ORDER BY f.id DESC LIMIT :lim""")
                .param("open", openOnly).param("hasCursor", key != null).param("cid", key == null ? 0L : key.id())
                .param("lim", limit + 1).query(this::row).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        return new CursorPage<>(toDtos(page), more ? Cursors.encode(0, page.getLast().id()) : null);
    }

    // ------------------------------------------------------------------ resolution

    public record Resolution(long flagId, long communityId, String status) {}

    /** Approve (release) or remove the content behind an open hold. Moderators of its community or admins. */
    @Transactional
    public Resolution resolve(long flagId, AuthUser user, boolean approve) {
        record F(long communityId, String status, String type, Long postId, Long commentId, Long authorId, String rules) {}
        F f = jdbc.sql("SELECT community_id, status, target_type, post_id, comment_id, author_id, rules FROM spam_flags WHERE id = ? FOR UPDATE")
                .param(flagId)
                .query((rs, i) -> new F(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4),
                        (Long) rs.getObject(5), (Long) rs.getObject(6), rs.getString(7)))
                .optional().orElseThrow(() -> ApiException.notFound("Flag"));
        Community c = communities.findById(f.communityId()).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        if (!"OPEN".equals(f.status()) || f.postId() == null) throw ApiException.badRequest("This flag was already resolved");
        boolean comment = "COMMENT".equals(f.type());
        if (approve) {
            release(f.postId(), comment ? f.commentId() : null, user);
            logModAction(c.getId(), user, "RELEASE_HELD", f.authorId(), f.postId(), f.commentId(), "Released after spam review");
        } else {
            closeHeld(f.postId(), comment ? f.commentId() : null, user, "REMOVED");
            long actionId = logModAction(c.getId(), user, comment ? "REMOVE_COMMENT" : "REMOVE_POST", f.authorId(), f.postId(),
                    f.commentId(), "Spam (" + f.rules().replace(",", ", ").toLowerCase().replace('_', ' ') + ")");
            if (f.authorId() != null) {
                notifications.contentRemoved(f.authorId(), c.getSlug(), comment ? "comment" : "post", actionId, "spam");
            }
        }
        realtime.modQueueChanged(c.getId());
        return new Resolution(flagId, c.getId(), approve ? "APPROVED" : "REMOVED");
    }

    /**
     * Makes held content visible and runs the side effects a normal publish would have had. No-op (false) when the
     * content isn't held. Also used when a moderator approves a held item from the post page.
     */
    @Transactional
    public boolean release(long postId, Long commentId, AuthUser by) {
        int n = commentId == null
                ? jdbc.sql("UPDATE posts SET removed = FALSE, pending = FALSE WHERE id = ? AND pending").param(postId).update()
                : jdbc.sql("UPDATE comments SET removed = FALSE, pending = FALSE WHERE id = ? AND pending").param(commentId).update();
        if (n == 0) return false;
        if (commentId == null) posts.released(postId);
        else comments.released(commentId);
        resolveFlags(postId, commentId, by, "APPROVED");
        return true;
    }

    /** Held content that a moderator removed: it stays removed but is no longer "pending"; its flags close. */
    @Transactional
    public void closeHeld(long postId, Long commentId, AuthUser by, String status) {
        if (commentId == null) jdbc.sql("UPDATE posts SET pending = FALSE WHERE id = ? AND pending").param(postId).update();
        else jdbc.sql("UPDATE comments SET pending = FALSE WHERE id = ? AND pending").param(commentId).update();
        resolveFlags(postId, commentId, by, status);
    }

    private void resolveFlags(long postId, Long commentId, AuthUser by, String status) {
        jdbc.sql("""
                        UPDATE spam_flags SET status = ?, resolved_by = ?, resolved_at = now()
                        WHERE status = 'OPEN' AND post_id = ? AND coalesce(comment_id, 0) = ?""")
                .params(status, by.id(), postId, commentId == null ? 0L : commentId).update();
    }

    private long logModAction(long communityId, AuthUser mod, String action, Long targetUser, Long postId, Long commentId, String reason) {
        return jdbc.sql("""
                        INSERT INTO moderation_actions (community_id, moderator_id, action, target_user_id, post_id, comment_id, reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(communityId, mod.id(), action, targetUser, postId, commentId, reason)
                .query(Long.class).single();
    }

    // ------------------------------------------------------------------ mapping

    private List<SpamFlagDto> toDtos(List<Row> rows) {
        Map<Long, UserSummary> u = directory.summaries(rows.stream().flatMap(r -> Stream.of(r.authorId(), r.resolvedBy())).toList());
        return rows.stream().map(r -> new SpamFlagDto(r.id(), new CommunityRef(r.communityId(), r.slug(), r.name(), r.icon()),
                r.authorId() == null ? null : u.get(r.authorId()), r.authorJoined(), r.type(), r.postId(), r.commentId(),
                r.postTitle(), Arrays.stream(r.rules().split(",")).filter(s -> !s.isBlank()).toList(), r.outcome(),
                r.details(), r.excerpt(), r.status(), r.resolvedBy() == null ? null : u.get(r.resolvedBy()), r.resolvedAt(),
                r.createdAt())).toList();
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getLong("id"), rs.getLong("community_id"), rs.getString("slug"), rs.getString("name"),
                rs.getString("icon_url"), (Long) rs.getObject("author_id"), ts(rs, "joined"), rs.getString("target_type"),
                (Long) rs.getObject("post_id"), (Long) rs.getObject("comment_id"), rs.getString("title"), rs.getString("rules"),
                rs.getString("outcome"), rs.getString("details"), rs.getString("excerpt"), rs.getString("status"),
                (Long) rs.getObject("resolved_by"), ts(rs, "resolved_at"), ts(rs, "created_at"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}

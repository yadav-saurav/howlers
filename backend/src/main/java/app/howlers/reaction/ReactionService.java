package app.howlers.reaction;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmService;
import app.howlers.notification.NotificationService;
import app.howlers.post.Post;
import app.howlers.post.PostService;
import app.howlers.post.PostService.PostContext;
import app.howlers.realtime.RealtimePublisher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emoji reactions on posts, comments and messages (D-044). One reaction of each kind per person per target, toggled on
 * and off. Every read and write re-checks that the reader can see the target right now (community visibility, held
 * content, removed or deleted content, conversation membership and blocks), so a reaction never reveals content the
 * reader can't open. Live updates reuse the thread topic ({@code /topic/post.{id}}) and the DM user queue; events carry
 * ids only, and clients refetch the summary under the same checks.
 */
@Service
public class ReactionService {
    public static final int SAMPLE_NAMES = 3;
    public static final int MAX_BATCH = 100;

    private final JdbcClient jdbc;
    private final PostService posts;
    private final DmService dms;
    private final app.howlers.community.CommunityAccess access;
    private final RateLimiter limiter;
    private final NotificationService notifications;
    private final RealtimePublisher realtime;

    public ReactionService(JdbcClient jdbc, PostService posts, DmService dms, app.howlers.community.CommunityAccess access,
                           RateLimiter limiter, NotificationService notifications, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.posts = posts;
        this.dms = dms;
        this.access = access;
        this.limiter = limiter;
        this.notifications = notifications;
        this.realtime = realtime;
    }

    public enum TargetType { POST, COMMENT, MESSAGE }

    /** {@code sample}: up to three usernames (earliest first, skipping people you blocked); {@code others}: the rest. */
    public record ReactionCount(String reaction, String emoji, String label, int count, boolean mine, List<String> sample,
                                int others) {}

    public record TargetReactions(String targetType, long targetId, List<ReactionCount> reactions) {}

    public record ToggleResult(boolean active, TargetReactions summary) {}

    /** What a reaction attaches to, after the visibility checks. */
    private record Target(TargetType type, long id, Long authorId, Long postId, Long communityId, Long conversationId,
                          String postTitle) {}

    public static TargetType parseType(String raw) {
        try {
            return TargetType.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.field("targetType", "Use POST, COMMENT or MESSAGE");
        }
    }

    // ---------------------------------------------------------------- reads

    /** Summaries for the targets the viewer can see; ids they can't see are left out (never says why). */
    @Transactional(readOnly = true)
    public List<TargetReactions> summaries(TargetType type, List<Long> rawIds, AuthUser user) {
        if (type == TargetType.MESSAGE && user == null) throw ApiException.unauthorized("Sign in to see message reactions");
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(rawIds == null ? List.of() : rawIds));
        if (ids.size() > MAX_BATCH) throw ApiException.field("ids", "Ask for up to " + MAX_BATCH + " ids at a time");
        List<Long> visible = ids.stream().filter(id -> visible(type, id, user)).toList();
        return aggregate(type, visible, user);
    }

    private boolean visible(TargetType type, long id, AuthUser user) {
        try {
            resolve(type, id, user);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- writes

    /** {@code on == null} toggles. Returns whether the reaction is now active and the fresh summary. */
    @Transactional
    public ToggleResult set(AuthUser user, TargetType type, long id, String reactionKey, Boolean on) {
        ReactionCatalog.Kind kind = ReactionCatalog.find(reactionKey)
                .orElseThrow(() -> ApiException.field("reaction", "Pick one of the available reactions"));
        limiter.check("react:" + user.id(), 60, Duration.ofMinutes(1));
        Target t = resolve(type, id, user);
        if (t.communityId() != null) access.requireNotBanned(t.communityId(), user);
        if (t.authorId() != null && t.authorId() != user.id() && blockedEitherWay(user.id(), t.authorId())) {
            throw ApiException.forbidden("You can't react to this");
        }
        boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM reactions WHERE target_type = ? AND target_id = ? AND user_id = ? AND reaction = ?)""")
                .params(type.name(), id, user.id(), kind.key()).query(Boolean.class).single();
        boolean want = on == null ? !exists : on;
        boolean changed;
        if (want) {
            changed = jdbc.sql("""
                            INSERT INTO reactions (target_type, target_id, user_id, reaction) VALUES (?, ?, ?, ?)
                            ON CONFLICT DO NOTHING""")
                    .params(type.name(), id, user.id(), kind.key()).update() == 1;
            // Notifications skip blocked and muted people (NotificationService.push) and your own content.
            if (changed && t.authorId() != null && type != TargetType.MESSAGE) {
                notifications.reaction(t.authorId(), user.id(), user.username(), kind.emoji(), type == TargetType.COMMENT,
                        t.postId(), type == TargetType.COMMENT ? id : null, t.postTitle());
            }
        } else {
            changed = jdbc.sql("DELETE FROM reactions WHERE target_type = ? AND target_id = ? AND user_id = ? AND reaction = ?")
                    .params(type.name(), id, user.id(), kind.key()).update() == 1;
        }
        if (changed) announce(t);
        List<TargetReactions> s = aggregate(type, List.of(id), user);
        return new ToggleResult(want, s.isEmpty() ? new TargetReactions(type.name(), id, List.of()) : s.getFirst());
    }

    private void announce(Target t) {
        if (t.type() == TargetType.MESSAGE) {
            realtime.toUsers(dms.memberIds(t.conversationId()), "REACTIONS",
                    Map.of("targetType", "MESSAGE", "targetId", t.id(), "conversationId", t.conversationId()));
        } else {
            realtime.reactionsChanged(t.postId(), t.type().name(), t.id());
        }
    }

    // ---------------------------------------------------------------- visibility

    /**
     * The target as {@code user} may react to it, or 404/400. Posts and comments must be live and not held; messages
     * must be people's (not system) messages that aren't deleted, in a conversation the reader belongs to, from someone
     * the reader hasn't blocked.
     */
    private Target resolve(TargetType type, long id, AuthUser user) {
        return switch (type) {
            case POST -> {
                PostContext ctx = posts.context(id, user);
                Post p = ctx.post();
                if (!p.isLive() || p.isPending()) throw ApiException.notFound("Post");
                yield new Target(type, id, p.getAuthorId(), id, ctx.community().getId(), null, p.getTitle());
            }
            case COMMENT -> {
                record C(long postId, Long authorId, boolean live) {}
                C c = jdbc.sql("""
                                SELECT post_id, author_id, deleted_at IS NULL AND removed = FALSE AND pending = FALSE
                                FROM comments WHERE id = ?""")
                        .param(id).query((rs, i) -> new C(rs.getLong(1), (Long) rs.getObject(2), rs.getBoolean(3)))
                        .optional().orElseThrow(() -> ApiException.notFound("Comment"));
                if (!c.live()) throw ApiException.notFound("Comment");
                PostContext ctx = posts.context(c.postId(), user);
                if (!ctx.post().isLive() || ctx.post().isPending()) throw ApiException.notFound("Comment");
                yield new Target(type, id, c.authorId(), c.postId(), ctx.community().getId(), null, ctx.post().getTitle());
            }
            case MESSAGE -> {
                if (user == null) throw ApiException.unauthorized("Sign in to react to messages");
                record M(long conversationId, Long senderId, boolean reactable) {}
                M m = jdbc.sql("SELECT conversation_id, sender_id, deleted_at IS NULL AND kind = 'USER' FROM dm_messages WHERE id = ?")
                        .param(id).query((rs, i) -> new M(rs.getLong(1), (Long) rs.getObject(2), rs.getBoolean(3)))
                        .optional().orElseThrow(() -> ApiException.notFound("Message"));
                dms.requireParticipant(m.conversationId(), user.id());
                if (!m.reactable()) throw ApiException.notFound("Message");
                if (m.senderId() != null && dms.load(id, user.id()).fromBlocked()) throw ApiException.notFound("Message");
                yield new Target(type, id, m.senderId(), null, null, m.conversationId(), null);
            }
        };
    }

    private boolean blockedEitherWay(long a, long b) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_blocks WHERE (blocker_id = ? AND blocked_id = ?)
                                                                    OR (blocker_id = ? AND blocked_id = ?))""")
                .params(a, b, b, a).query(Boolean.class).single();
    }

    // ---------------------------------------------------------------- aggregation

    private List<TargetReactions> aggregate(TargetType type, List<Long> ids, AuthUser user) {
        if (ids.isEmpty()) return List.of();
        long viewer = user == null ? 0L : user.id();
        record Count(long targetId, String reaction, int n, boolean mine) {}
        List<Count> counts = jdbc.sql("""
                        SELECT target_id, reaction, count(*) AS n, bool_or(user_id = :viewer) AS mine
                        FROM reactions WHERE target_type = :t AND target_id IN (:ids)
                        GROUP BY target_id, reaction""")
                .param("viewer", viewer).param("t", type.name()).param("ids", ids)
                .query((rs, i) -> new Count(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)))
                .list();
        Map<String, List<String>> samples = new HashMap<>();
        jdbc.sql("""
                        SELECT target_id, reaction, username FROM (
                            SELECT r.target_id, r.reaction, u.username,
                                   row_number() OVER (PARTITION BY r.target_id, r.reaction ORDER BY r.created_at, r.user_id) AS rn
                            FROM reactions r JOIN users u ON u.id = r.user_id AND u.deleted_at IS NULL
                            WHERE r.target_type = :t AND r.target_id IN (:ids)
                              AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.blocker_id = :viewer AND b.blocked_id = r.user_id)
                        ) x WHERE rn <= :n ORDER BY target_id, reaction, rn""")
                .param("t", type.name()).param("ids", ids).param("viewer", viewer).param("n", SAMPLE_NAMES)
                .query((rs, i) -> samples.computeIfAbsent(rs.getLong(1) + ":" + rs.getString(2), k -> new ArrayList<>())
                        .add(rs.getString(3)))
                .list();
        Map<Long, List<ReactionCount>> byTarget = new HashMap<>();
        for (Count c : counts) {
            ReactionCatalog.Kind kind = ReactionCatalog.find(c.reaction()).orElse(null);
            if (kind == null) continue; // retired from the catalog: kept in the table, not shown
            List<String> sample = samples.getOrDefault(c.targetId() + ":" + c.reaction(), List.of());
            byTarget.computeIfAbsent(c.targetId(), k -> new ArrayList<>()).add(new ReactionCount(kind.key(), kind.emoji(),
                    kind.label(), c.n(), c.mine(), sample, Math.max(0, c.n() - sample.size())));
        }
        List<TargetReactions> out = new ArrayList<>();
        for (long id : ids) {
            List<ReactionCount> list = new ArrayList<>(byTarget.getOrDefault(id, List.of()));
            list.sort(Comparator.comparingInt((ReactionCount r) -> ReactionCatalog.order(r.reaction())));
            out.add(new TargetReactions(type.name(), id, list));
        }
        return out;
    }
}

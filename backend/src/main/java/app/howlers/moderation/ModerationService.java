package app.howlers.moderation;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityMapper;
import app.howlers.community.CommunityRepository;
import app.howlers.community.CommunityRole;
import app.howlers.community.MembershipDao;
import app.howlers.insight.TextSignals;
import app.howlers.moderation.ModerationDtos.Action;
import app.howlers.moderation.ModerationDtos.Appeal;
import app.howlers.moderation.ModerationDtos.Ban;
import app.howlers.moderation.ModerationDtos.ModAction;
import app.howlers.moderation.ModerationDtos.ModStats;
import app.howlers.moderation.ModerationDtos.MyModAction;
import app.howlers.moderation.ModerationDtos.QueueItem;
import app.howlers.moderation.ModerationDtos.ReasonCount;
import app.howlers.moderation.ModerationDtos.ReportRequest;
import app.howlers.notification.NotificationService;
import app.howlers.post.PostService;
import app.howlers.post.PostService.PostContext;
import app.howlers.user.User;
import app.howlers.user.UserDirectory;
import app.howlers.user.UserRepository;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Community moderation. Every state change is recorded in {@code moderation_actions} (append-only; reversal is
 * a flag, never a delete). Permissions come from {@link CommunityAccess}; nothing here trusts the client.
 */
@Service
public class ModerationService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);
    private static final int MAX_PINNED = 3;

    private final JdbcClient jdbc;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final MembershipDao memberships;
    private final PostService posts;
    private final UserRepository users;
    private final UserDirectory directory;
    private final NotificationService notifications;
    private final RateLimiter limiter;
    private final app.howlers.realtime.RealtimePublisher realtime;
    private final app.howlers.reputation.ReputationQueue reputation;
    private final app.howlers.spam.SpamService spam;

    public ModerationService(JdbcClient jdbc, CommunityRepository communities, CommunityAccess access, MembershipDao memberships,
                             PostService posts, UserRepository users, UserDirectory directory, NotificationService notifications,
                             RateLimiter limiter, app.howlers.realtime.RealtimePublisher realtime,
                             app.howlers.reputation.ReputationQueue reputation, app.howlers.spam.SpamService spam) {
        this.realtime = realtime;
        this.reputation = reputation;
        this.spam = spam;
        this.jdbc = jdbc;
        this.communities = communities;
        this.access = access;
        this.memberships = memberships;
        this.posts = posts;
        this.users = users;
        this.directory = directory;
        this.notifications = notifications;
        this.limiter = limiter;
    }

    // ------------------------------------------------------------------ reports

    @Transactional
    public void report(AuthUser user, ReportRequest req) {
        limiter.check("report:" + user.id(), 20, Duration.ofHours(1));
        PostContext ctx = posts.context(req.postId(), user);
        boolean comment = "COMMENT".equals(req.targetType());
        Long authorId = ctx.post().getAuthorId();
        if (comment) {
            if (req.commentId() == null) throw ApiException.field("commentId", "Choose a comment to report");
            record C(long postId, Long authorId) {}
            C c = jdbc.sql("SELECT post_id, author_id FROM comments WHERE id = ? AND deleted_at IS NULL")
                    .param(req.commentId()).query((rs, i) -> new C(rs.getLong(1), (Long) rs.getObject(2))).optional()
                    .orElseThrow(() -> ApiException.notFound("Comment"));
            if (c.postId() != req.postId()) throw ApiException.field("commentId", "That comment is not on this post");
            authorId = c.authorId();
        }
        if (Objects.equals(authorId, user.id())) throw ApiException.badRequest("You can't report your own content");
        try {
            jdbc.sql("""
                            INSERT INTO reports (community_id, reporter_id, target_type, post_id, comment_id, reason, details)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""")
                    .params(ctx.community().getId(), user.id(), req.targetType(), req.postId(), comment ? req.commentId() : null,
                            req.reason().strip(), req.details() == null ? "" : req.details().strip())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(null, "You already reported this");
        }
        realtime.modQueueChanged(ctx.community().getId());
    }

    @Transactional(readOnly = true)
    public List<QueueItem> queue(String slug, AuthUser user) {
        Community c = moderatorOf(slug, user);
        record R(String type, long postId, Long commentId, String reason, String details, Instant at) {}
        List<R> rows = jdbc.sql("""
                        SELECT target_type, post_id, comment_id, reason, details, created_at FROM reports
                        WHERE community_id = ? AND status = 'OPEN' ORDER BY created_at DESC LIMIT 2000""")
                .param(c.getId())
                .query((rs, i) -> new R(rs.getString(1), rs.getLong(2), (Long) rs.getObject(3), rs.getString(4), rs.getString(5),
                        ts(rs, 6)))
                .list();
        Map<String, List<R>> grouped = new LinkedHashMap<>();
        for (R r : rows) grouped.computeIfAbsent(r.type() + ":" + r.postId() + ":" + r.commentId(), k -> new ArrayList<>()).add(r);

        List<QueueItem> out = new ArrayList<>();
        for (List<R> g : grouped.values()) {
            R first = g.getFirst();
            Target t = target(first.postId(), first.commentId());
            if (t == null) continue;
            Map<String, Integer> reasons = new LinkedHashMap<>();
            g.forEach(r -> reasons.merge(r.reason(), 1, Integer::sum));
            out.add(new QueueItem(first.type(), first.postId(), first.commentId(), t.title(), t.excerpt(), t.author(), t.removed(),
                    t.locked(), t.pinned(), t.createdAt(), g.size(),
                    reasons.entrySet().stream().map(e -> new ReasonCount(e.getKey(), e.getValue()))
                            .sorted((a, b) -> b.count() - a.count()).toList(),
                    g.stream().map(R::details).filter(d -> !d.isBlank()).limit(5).toList(),
                    g.getLast().at(), first.at()));
        }
        out.sort((a, b) -> b.reportCount() != a.reportCount() ? b.reportCount() - a.reportCount()
                : b.lastReportedAt().compareTo(a.lastReportedAt()));
        return out.size() > 100 ? out.subList(0, 100) : out;
    }

    private record Target(String title, String excerpt, UserSummary author, boolean removed, boolean locked, boolean pinned,
                          Instant createdAt) {}

    private Target target(long postId, Long commentId) {
        record P(String title, String body, Long authorId, boolean removed, boolean locked, boolean pinned, Instant at) {}
        P p = jdbc.sql("SELECT title, body_text, author_id, removed, locked, pinned, created_at FROM posts WHERE id = ? AND deleted_at IS NULL")
                .param(postId)
                .query((rs, i) -> new P(rs.getString(1), rs.getString(2), (Long) rs.getObject(3), rs.getBoolean(4), rs.getBoolean(5),
                        rs.getBoolean(6), ts(rs, 7)))
                .optional().orElse(null);
        if (p == null) return null;
        if (commentId == null) {
            return new Target(p.title(), TextSignals.excerpt(p.body(), 280), directory.summaries(Stream.of(p.authorId()).toList()).get(p.authorId()),
                    p.removed(), p.locked(), p.pinned(), p.at());
        }
        record C(String body, Long authorId, boolean removed, Instant at) {}
        C c = jdbc.sql("SELECT body, author_id, removed, created_at FROM comments WHERE id = ? AND deleted_at IS NULL")
                .param(commentId).query((rs, i) -> new C(rs.getString(1), (Long) rs.getObject(2), rs.getBoolean(3), ts(rs, 4)))
                .optional().orElse(null);
        if (c == null) return null;
        return new Target(p.title(), TextSignals.excerpt(c.body(), 280), directory.summaries(Stream.of(c.authorId()).toList()).get(c.authorId()),
                c.removed(), p.locked(), false, c.at());
    }

    // ------------------------------------------------------------------ content actions

    /** Applies a post action; returns the post id so the caller can render the updated post. */
    @Transactional
    public long postAction(long postId, AuthUser user, Action action, String reason) {
        PostContext ctx = posts.context(postId, user);
        access.requireModerator(ctx.community(), user);
        if (ctx.post().getDeletedAt() != null) throw ApiException.notFound("Post");
        long communityId = ctx.community().getId();
        realtime.modQueueChanged(communityId);
        Long author = ctx.post().getAuthorId();
        switch (action) {
            case REMOVE_POST -> {
                jdbc.sql("UPDATE posts SET removed = TRUE, pinned = FALSE WHERE id = ?").param(postId).update();
                reputation.markDirty(author); // votes on removed content stop counting
                if (ctx.post().isPending()) spam.closeHeld(postId, null, user, "REMOVED");
                long id = log(communityId, user, action, author, postId, null, reason, null);
                resolveReports(postId, null, user, "REMOVED");
                if (author != null) notifications.contentRemoved(author, ctx.community().getSlug(), "post", id, reason);
            }
            case APPROVE_POST -> {
                // A held post is released (counters, notifications, live event); anything else is simply restored.
                if (!spam.release(postId, null, user)) {
                    jdbc.sql("UPDATE posts SET removed = FALSE WHERE id = ?").param(postId).update();
                }
                reputation.markDirty(author);
                reverseOpen(postId, null, Action.REMOVE_POST, user);
                log(communityId, user, action, author, postId, null, reason, null);
                resolveReports(postId, null, user, "APPROVED");
            }
            case LOCK, UNLOCK -> {
                jdbc.sql("UPDATE posts SET locked = ? WHERE id = ?").params(action == Action.LOCK, postId).update();
                log(communityId, user, action, author, postId, null, reason, null);
            }
            case PIN -> {
                if (ctx.post().isRemoved()) throw ApiException.badRequest("Approve the post before pinning it");
                int pinned = jdbc.sql("SELECT count(*) FROM posts WHERE community_id = ? AND pinned AND deleted_at IS NULL AND id <> ?")
                        .params(communityId, postId).query(Integer.class).single();
                if (pinned >= MAX_PINNED) throw ApiException.badRequest("A community can pin at most " + MAX_PINNED + " posts");
                jdbc.sql("UPDATE posts SET pinned = TRUE WHERE id = ?").param(postId).update();
                log(communityId, user, action, author, postId, null, reason, null);
            }
            case UNPIN -> {
                jdbc.sql("UPDATE posts SET pinned = FALSE WHERE id = ?").param(postId).update();
                log(communityId, user, action, author, postId, null, reason, null);
            }
            default -> throw ApiException.badRequest("Unsupported post action");
        }
        return postId;
    }

    @Transactional
    public void commentAction(long commentId, AuthUser user, Action action, String reason) {
        record C(long postId, Long authorId) {}
        C c = jdbc.sql("SELECT post_id, author_id FROM comments WHERE id = ? AND deleted_at IS NULL").param(commentId)
                .query((rs, i) -> new C(rs.getLong(1), (Long) rs.getObject(2))).optional()
                .orElseThrow(() -> ApiException.notFound("Comment"));
        PostContext ctx = posts.context(c.postId(), user);
        access.requireModerator(ctx.community(), user);
        long communityId = ctx.community().getId();
        realtime.modQueueChanged(communityId);
        if (action == Action.REMOVE_COMMENT) {
            jdbc.sql("UPDATE comments SET removed = TRUE WHERE id = ?").param(commentId).update();
            reputation.markDirty(c.authorId());
            spam.closeHeld(c.postId(), commentId, user, "REMOVED");
            long id = log(communityId, user, action, c.authorId(), c.postId(), commentId, reason, null);
            resolveReports(c.postId(), commentId, user, "REMOVED");
            if (c.authorId() != null) notifications.contentRemoved(c.authorId(), ctx.community().getSlug(), "comment", id, reason);
        } else if (action == Action.APPROVE_COMMENT) {
            if (!spam.release(c.postId(), commentId, user)) {
                jdbc.sql("UPDATE comments SET removed = FALSE WHERE id = ?").param(commentId).update();
            }
            reputation.markDirty(c.authorId());
            reverseOpen(c.postId(), commentId, Action.REMOVE_COMMENT, user);
            log(communityId, user, action, c.authorId(), c.postId(), commentId, reason, null);
            resolveReports(c.postId(), commentId, user, "APPROVED");
        } else {
            throw ApiException.badRequest("Unsupported comment action");
        }
    }

    private void resolveReports(long postId, Long commentId, AuthUser by, String resolution) {
        jdbc.sql("""
                        UPDATE reports SET status = 'RESOLVED', resolution = ?, resolved_by = ?, resolved_at = now()
                        WHERE status = 'OPEN' AND post_id = ? AND coalesce(comment_id, 0) = ?""")
                .params(resolution, by.id(), postId, commentId == null ? 0L : commentId).update();
    }

    /** When content is approved after a removal, the removal is marked reversed so the log tells the full story. */
    private void reverseOpen(long postId, Long commentId, Action removal, AuthUser by) {
        jdbc.sql("""
                        UPDATE moderation_actions SET reversed_at = now(), reversed_by = ?
                        WHERE action = ? AND post_id = ? AND coalesce(comment_id, 0) = ? AND reversed_at IS NULL""")
                .params(by.id(), removal.name(), postId, commentId == null ? 0L : commentId).update();
    }

    private long log(long communityId, AuthUser mod, Action action, Long targetUser, Long postId, Long commentId, String reason,
                     Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO moderation_actions (community_id, moderator_id, action, target_user_id, post_id, comment_id, reason, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(communityId, mod.id(), action.name(), targetUser, postId, commentId,
                        reason == null || reason.isBlank() ? null : reason.strip(), expiresAt == null ? null : Timestamp.from(expiresAt))
                .query(Long.class).single();
    }

    // ------------------------------------------------------------------ bans

    @Transactional
    public Ban ban(String slug, AuthUser actor, String username, String reason, Integer days) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        CommunityRole actorRole = access.requireModerator(c, actor);
        User target = users.findByUsernameIgnoreCase(username).filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.field("username", "No user with that name"));
        if (target.getId() == actor.id()) throw ApiException.field("username", "You can't ban yourself");
        Optional<CommunityRole> targetRole = memberships.role(c.getId(), target.getId());
        if (targetRole.isPresent() && targetRole.get() == CommunityRole.OWNER) throw ApiException.forbidden("The owner can't be banned");
        // Moderators can only be banned by someone strictly above them (e.g. an admin bans a moderator, the owner bans an admin).
        if (targetRole.isPresent() && targetRole.get().isModerator() && actorRole.ordinal() <= targetRole.get().ordinal()) {
            throw ApiException.forbidden("You can't ban a moderator of equal or higher rank");
        }
        Instant expires = days == null ? null : Instant.now().plus(Duration.ofDays(days));
        long actionId = log(c.getId(), actor, Action.BAN, target.getId(), null, null, reason, expires);
        jdbc.sql("""
                        INSERT INTO community_bans (community_id, user_id, reason, expires_at, banned_by, action_id) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (community_id, user_id) DO UPDATE SET reason = EXCLUDED.reason, expires_at = EXCLUDED.expires_at,
                          banned_by = EXCLUDED.banned_by, action_id = EXCLUDED.action_id, created_at = now()""")
                .params(c.getId(), target.getId(), reason.strip(), expires == null ? null : Timestamp.from(expires), actor.id(), actionId)
                .update();
        if (targetRole.isPresent() && targetRole.get().isModerator()) {
            memberships.setRole(c.getId(), target.getId(), CommunityRole.MEMBER); // a banned moderator loses their powers
        }
        notifications.banned(target.getId(), c.getSlug(), actionId, expires == null ? "permanently" : "until " + DAY.format(expires));
        return bans(slug, actor).stream().filter(b -> b.user().id() == target.getId()).findFirst().orElseThrow();
    }

    @Transactional
    public void unban(String slug, AuthUser actor, String username) {
        Community c = moderatorOf(slug, actor);
        User target = users.findByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("User"));
        int n = jdbc.sql("DELETE FROM community_bans WHERE community_id = ? AND user_id = ?").params(c.getId(), target.getId()).update();
        if (n == 0) throw ApiException.notFound("Ban");
        jdbc.sql("UPDATE moderation_actions SET reversed_at = now(), reversed_by = ? WHERE community_id = ? AND target_user_id = ? AND action = 'BAN' AND reversed_at IS NULL")
                .params(actor.id(), c.getId(), target.getId()).update();
        log(c.getId(), actor, Action.UNBAN, target.getId(), null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<Ban> bans(String slug, AuthUser user) {
        Community c = moderatorOf(slug, user);
        record B(long userId, String reason, Instant expires, Long by, Instant at) {}
        List<B> rows = jdbc.sql("""
                        SELECT user_id, reason, expires_at, banned_by, created_at FROM community_bans
                        WHERE community_id = ? AND (expires_at IS NULL OR expires_at > now()) ORDER BY created_at DESC""")
                .param(c.getId())
                .query((rs, i) -> new B(rs.getLong(1), rs.getString(2), ts(rs, 3), (Long) rs.getObject(4), ts(rs, 5))).list();
        Map<Long, UserSummary> u = directory.summaries(rows.stream().flatMap(b -> Stream.of(b.userId(), b.by())).toList());
        return rows.stream().filter(b -> u.containsKey(b.userId()))
                .map(b -> new Ban(u.get(b.userId()), b.reason(), b.expires(), b.by() == null ? null : u.get(b.by()), b.at())).toList();
    }

    // ------------------------------------------------------------------ log, stats

    private static final String ACTION_COLS = """
            a.id, a.action, a.moderator_id, a.target_user_id, a.post_id, a.comment_id, p.title, a.reason, a.expires_at,
            a.reversed_at IS NOT NULL AS reversed, a.created_at, a.community_id""";

    private record ActionRow(long id, String action, Long moderatorId, Long targetUserId, Long postId, Long commentId,
                             String title, String reason, Instant expires, boolean reversed, Instant at, long communityId) {}

    private ActionRow actionRow(ResultSet rs, int i) throws SQLException {
        return new ActionRow(rs.getLong("id"), rs.getString("action"), (Long) rs.getObject("moderator_id"),
                (Long) rs.getObject("target_user_id"), (Long) rs.getObject("post_id"), (Long) rs.getObject("comment_id"),
                rs.getString("title"), rs.getString("reason"), ts(rs, "expires_at"), rs.getBoolean("reversed"), ts(rs, "created_at"),
                rs.getLong("community_id"));
    }

    private List<ModAction> toActions(List<ActionRow> rows) {
        Map<Long, UserSummary> u = directory.summaries(rows.stream().flatMap(r -> Stream.of(r.moderatorId(), r.targetUserId())).toList());
        return rows.stream().map(r -> new ModAction(r.id(), r.action(), r.moderatorId() == null ? null : u.get(r.moderatorId()),
                r.targetUserId() == null ? null : u.get(r.targetUserId()), r.postId(), r.commentId(), r.title(), r.reason(),
                r.expires(), r.reversed(), r.at())).toList();
    }

    @Transactional(readOnly = true)
    public CursorPage<ModAction> log(String slug, AuthUser user, String cursor, int limit) {
        Community c = moderatorOf(slug, user);
        Cursors.Key key = Cursors.decode(cursor);
        List<ActionRow> rows = jdbc.sql("SELECT " + ACTION_COLS + """

                        FROM moderation_actions a LEFT JOIN posts p ON p.id = a.post_id
                        WHERE a.community_id = :c AND (:hasCursor = FALSE OR a.id < :cid) ORDER BY a.id DESC LIMIT :lim""")
                .param("c", c.getId()).param("hasCursor", key != null).param("cid", key == null ? 0L : key.id()).param("lim", limit + 1)
                .query(this::actionRow).list();
        boolean more = rows.size() > limit;
        List<ActionRow> page = more ? rows.subList(0, limit) : rows;
        return new CursorPage<>(toActions(page), more ? Cursors.encode(0, page.getLast().id()) : null);
    }

    @Transactional(readOnly = true)
    public ModStats stats(String slug, AuthUser user) {
        Community c = moderatorOf(slug, user);
        long cid = c.getId();
        long open = jdbc.sql("SELECT count(DISTINCT (post_id, coalesce(comment_id, 0))) FROM reports WHERE community_id = ? AND status = 'OPEN'")
                .param(cid).query(Long.class).single();
        long appeals = jdbc.sql("SELECT count(*) FROM appeals ap JOIN moderation_actions a ON a.id = ap.action_id WHERE a.community_id = ? AND ap.status = 'PENDING'")
                .param(cid).query(Long.class).single();
        long bans = jdbc.sql("SELECT count(*) FROM community_bans WHERE community_id = ? AND (expires_at IS NULL OR expires_at > now())")
                .param(cid).query(Long.class).single();
        long actions = jdbc.sql("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND created_at > now() - interval '30 days'")
                .param(cid).query(Long.class).single();
        long removed = jdbc.sql("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action IN ('REMOVE_POST','REMOVE_COMMENT') AND created_at > now() - interval '30 days'")
                .param(cid).query(Long.class).single();
        return new ModStats(open, appeals, bans, actions, removed, spam.openHoldCount(cid));
    }

    // ------------------------------------------------------------------ appeals

    private Appeal appeal(long appealId) {
        record A(long id, long actionId, long userId, String message, String status, String response, Instant at, Instant resolved) {}
        A a = jdbc.sql("SELECT id, action_id, user_id, message, status, response, created_at, resolved_at FROM appeals WHERE id = ?")
                .param(appealId)
                .query((rs, i) -> new A(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        ts(rs, 7), ts(rs, 8)))
                .single();
        ModAction action = actionById(a.actionId());
        UserSummary u = directory.summaries(List.of(a.userId())).get(a.userId());
        return new Appeal(a.id(), action, u, a.message(), a.status(), a.response(), a.at(), a.resolved());
    }

    private ModAction actionById(long id) {
        return toActions(jdbc.sql("SELECT " + ACTION_COLS + " FROM moderation_actions a LEFT JOIN posts p ON p.id = a.post_id WHERE a.id = ?")
                .param(id).query(this::actionRow).list()).getFirst();
    }

    @Transactional(readOnly = true)
    public List<Appeal> appeals(String slug, AuthUser user, boolean pendingOnly) {
        Community c = moderatorOf(slug, user);
        List<Long> ids = jdbc.sql("""
                        SELECT ap.id FROM appeals ap JOIN moderation_actions a ON a.id = ap.action_id
                        WHERE a.community_id = ? AND (? = FALSE OR ap.status = 'PENDING') ORDER BY ap.id DESC LIMIT 200""")
                .params(c.getId(), pendingOnly).query(Long.class).list();
        return ids.stream().map(this::appeal).toList();
    }

    @Transactional(readOnly = true)
    public MyModAction myAction(long actionId, AuthUser user) {
        Long target = jdbc.sql("SELECT target_user_id FROM moderation_actions WHERE id = ?").param(actionId)
                .query(Long.class).optional().orElse(null);
        if (target == null || target != user.id()) throw ApiException.notFound("Moderation action");
        ModAction a = actionById(actionId);
        long communityId = jdbc.sql("SELECT community_id FROM moderation_actions WHERE id = ?").param(actionId).query(Long.class).single();
        Community c = communities.findById(communityId).orElseThrow(() -> ApiException.notFound("Community"));
        Optional<Long> appealId = jdbc.sql("SELECT id FROM appeals WHERE action_id = ?").param(actionId).query(Long.class).optional();
        boolean appealable = Stream.of("REMOVE_POST", "REMOVE_COMMENT", "BAN").anyMatch(a.action()::equals) && !a.reversed();
        return new MyModAction(withoutModerator(a), CommunityMapper.ref(c), appealId.map(this::appeal).map(this::withoutModerator).orElse(null),
                appealable && appealId.isEmpty());
    }

    @Transactional
    public Appeal submitAppeal(long actionId, AuthUser user, String message) {
        MyModAction mine = myAction(actionId, user);
        if (!mine.canAppeal()) throw ApiException.badRequest(mine.appeal() != null ? "You already appealed this" : "This action can't be appealed");
        long id = jdbc.sql("INSERT INTO appeals (action_id, user_id, message) VALUES (?, ?, ?) RETURNING id")
                .params(actionId, user.id(), message.strip()).query(Long.class).single();
        return withoutModerator(appeal(id));
    }

    /**
     * What the affected user sees: actions are taken on behalf of the moderation team (D-014), so the individual
     * moderator is never named to them. Moderators still see who did what in the log and the appeals queue.
     */
    private ModAction withoutModerator(ModAction a) {
        return new ModAction(a.id(), a.action(), null, a.targetUser(), a.postId(), a.commentId(), a.targetTitle(), a.reason(),
                a.expiresAt(), a.reversed(), a.createdAt());
    }

    private Appeal withoutModerator(Appeal ap) {
        return new Appeal(ap.id(), withoutModerator(ap.action()), ap.user(), ap.message(), ap.status(), ap.response(), ap.createdAt(),
                ap.resolvedAt());
    }

    @Transactional
    public Appeal resolveAppeal(long appealId, AuthUser user, boolean accept, String response) {
        record A(long actionId, long userId, String status, long communityId, String action, Long postId, Long commentId) {}
        A a = jdbc.sql("""
                        SELECT ap.action_id, ap.user_id, ap.status, m.community_id, m.action, m.post_id, m.comment_id
                        FROM appeals ap JOIN moderation_actions m ON m.id = ap.action_id WHERE ap.id = ?""")
                .param(appealId)
                .query((rs, i) -> new A(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        (Long) rs.getObject(6), (Long) rs.getObject(7)))
                .optional().orElseThrow(() -> ApiException.notFound("Appeal"));
        Community c = communities.findById(a.communityId()).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        if (!"PENDING".equals(a.status())) throw ApiException.badRequest("This appeal was already resolved");
        if (accept) {
            switch (a.action()) {
                case "REMOVE_POST" -> jdbc.sql("UPDATE posts SET removed = FALSE WHERE id = ?").param(a.postId()).update();
                case "REMOVE_COMMENT" -> jdbc.sql("UPDATE comments SET removed = FALSE WHERE id = ?").param(a.commentId()).update();
                case "BAN" -> jdbc.sql("DELETE FROM community_bans WHERE community_id = ? AND user_id = ?").params(a.communityId(), a.userId()).update();
                default -> { }
            }
            jdbc.sql("UPDATE moderation_actions SET reversed_at = now(), reversed_by = ? WHERE id = ?").params(user.id(), a.actionId()).update();
            if (!"BAN".equals(a.action())) reputation.markDirty(a.userId()); // restored content counts again
        }
        jdbc.sql("UPDATE appeals SET status = ?, response = ?, resolved_by = ?, resolved_at = now() WHERE id = ?")
                .params(accept ? "ACCEPTED" : "REJECTED", response == null || response.isBlank() ? null : response.strip(), user.id(), appealId)
                .update();
        notifications.appealResolved(a.userId(), c.getSlug(), accept, a.actionId());
        return appeal(appealId);
    }

    // ------------------------------------------------------------------ blocks

    @Transactional
    public void block(AuthUser user, String username, boolean block) {
        User target = users.findByUsernameIgnoreCase(username).filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("User"));
        if (target.getId() == user.id()) throw ApiException.badRequest("You can't block yourself");
        if (block) {
            jdbc.sql("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING").params(user.id(), target.getId()).update();
            // Blocking also removes any follow relationship in both directions.
            jdbc.sql("DELETE FROM user_follows WHERE (follower_id = ? AND followee_id = ?) OR (follower_id = ? AND followee_id = ?)")
                    .params(user.id(), target.getId(), target.getId(), user.id()).update();
            jdbc.sql("UPDATE users u SET follower_count = (SELECT count(*) FROM user_follows f WHERE f.followee_id = u.id), "
                    + "following_count = (SELECT count(*) FROM user_follows f WHERE f.follower_id = u.id) WHERE u.id IN (?, ?)")
                    .params(user.id(), target.getId()).update();
        } else {
            jdbc.sql("DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?").params(user.id(), target.getId()).update();
        }
    }

    @Transactional(readOnly = true)
    public List<UserSummary> blocks(AuthUser user) {
        List<Long> ids = jdbc.sql("SELECT blocked_id FROM user_blocks WHERE blocker_id = ? ORDER BY created_at DESC").param(user.id())
                .query(Long.class).list();
        Map<Long, UserSummary> u = directory.summaries(ids);
        return ids.stream().map(u::get).filter(Objects::nonNull).toList();
    }

    // ------------------------------------------------------------------ helpers

    private Community moderatorOf(String slug, AuthUser user) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        return c;
    }

    private static Instant ts(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}

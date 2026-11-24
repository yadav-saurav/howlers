package app.howlers.admin;

import app.howlers.admin.AdminDtos.AdminCommunity;
import app.howlers.admin.AdminDtos.AdminReport;
import app.howlers.admin.AdminDtos.AdminUser;
import app.howlers.admin.AdminDtos.AdminUserDetail;
import app.howlers.admin.AdminDtos.AuditEntry;
import app.howlers.admin.AdminDtos.DayCount;
import app.howlers.admin.AdminDtos.Overview;
import app.howlers.auth.RefreshTokenRepository;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AccountAccessRevoked;
import app.howlers.common.security.AccountStatusCache;
import org.springframework.context.ApplicationEventPublisher;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.insight.TextSignals;
import app.howlers.notification.NotificationService;
import app.howlers.spam.SpamService;
import app.howlers.spam.SpamService.Resolution;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform administration (D-021). Access is enforced twice: {@code /api/v1/admin/**} requires the ADMIN role in
 * SecurityConfig, and every method here re-checks it. Every state change writes one row to the append-only
 * {@code admin_actions} table in the same transaction.
 */
@Service
public class AdminService {
    private final JdbcClient jdbc;
    private final UserDirectory directory;
    private final RefreshTokenRepository refreshTokens;
    private final AccountStatusCache accounts;
    private final SpamService spam;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;

    public AdminService(JdbcClient jdbc, UserDirectory directory, RefreshTokenRepository refreshTokens,
                        AccountStatusCache accounts, SpamService spam, NotificationService notifications,
                        ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.directory = directory;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.spam = spam;
        this.notifications = notifications;
    }

    private static void requireAdmin(AuthUser user) {
        if (user == null || !user.isAdmin()) throw ApiException.forbidden("Admins only");
    }

    // ------------------------------------------------------------------ overview

    @Transactional(readOnly = true)
    public Overview overview(AuthUser admin) {
        requireAdmin(admin);
        record Counts(long users, long newUsers, long suspended, long communities, long quarantined, long banned, long posts,
                      long comments, long reports, long flags) {}
        Counts c = jdbc.sql("""
                        SELECT (SELECT count(*) FROM users WHERE status <> 'DELETED'),
                               (SELECT count(*) FROM users WHERE status <> 'DELETED' AND created_at > now() - interval '7 days'),
                               (SELECT count(*) FROM users WHERE status = 'SUSPENDED'),
                               (SELECT count(*) FROM communities),
                               (SELECT count(*) FROM communities WHERE status = 'QUARANTINED'),
                               (SELECT count(*) FROM communities WHERE status = 'BANNED'),
                               (SELECT count(*) FROM posts WHERE deleted_at IS NULL AND removed = FALSE),
                               (SELECT count(*) FROM comments WHERE deleted_at IS NULL AND removed = FALSE),
                               (SELECT count(DISTINCT (post_id, coalesce(comment_id, 0))) FROM reports WHERE status = 'OPEN'),
                               (SELECT count(*) FROM spam_flags WHERE status = 'OPEN')""")
                .query((rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10)))
                .single();
        // One row per UTC day for the last 14 days, zero-filled, so the chart never invents or skips days.
        List<DayCount> signups = jdbc.sql("""
                        SELECT to_char(d.day, 'YYYY-MM-DD'), count(u.id)
                        FROM generate_series((now() AT TIME ZONE 'UTC')::date - 13, (now() AT TIME ZONE 'UTC')::date, interval '1 day') AS d(day)
                        LEFT JOIN users u ON (u.created_at AT TIME ZONE 'UTC')::date = d.day::date AND u.status <> 'DELETED'
                        GROUP BY d.day ORDER BY d.day""")
                .query((rs, i) -> new DayCount(rs.getString(1), rs.getLong(2))).list();
        return new Overview(c.users(), c.newUsers(), c.suspended(), c.communities(), c.quarantined(), c.banned(), c.posts(),
                c.comments(), c.reports(), c.flags(), signups);
    }

    // ------------------------------------------------------------------ users

    private static final String USER_COLS = """
            u.id, u.username, u.display_name, u.avatar_url, u.email, u.role, u.status, u.email_verified, u.created_at,
            u.post_karma, u.comment_karma, u.suspended_reason, u.suspended_at""";

    private record UserRow(long id, String username, String displayName, String avatarUrl, String email, String role,
                           String status, boolean verified, Instant createdAt, int postKarma, int commentKarma,
                           String suspendedReason, Instant suspendedAt) {}

    private UserRow userRow(ResultSet rs, int i) throws SQLException {
        return new UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"), rs.getString("avatar_url"),
                rs.getString("email"), rs.getString("role"), rs.getString("status"), rs.getBoolean("email_verified"),
                ts(rs, "created_at"), rs.getInt("post_karma"), rs.getInt("comment_karma"), rs.getString("suspended_reason"),
                ts(rs, "suspended_at"));
    }

    private List<AdminUser> toAdminUsers(List<UserRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(UserRow::id).toList();
        Map<Long, Long> posts = counts("SELECT author_id, count(*) FROM posts WHERE author_id IN (:ids) AND deleted_at IS NULL GROUP BY author_id", ids);
        Map<Long, Long> comments = counts("SELECT author_id, count(*) FROM comments WHERE author_id IN (:ids) AND deleted_at IS NULL GROUP BY author_id", ids);
        return rows.stream().map(r -> new AdminUser(r.id(), r.username(), r.displayName(), r.avatarUrl(), r.email(), r.role(),
                r.status(), r.verified(), r.createdAt(), r.postKarma(), r.commentKarma(), posts.getOrDefault(r.id(), 0L),
                comments.getOrDefault(r.id(), 0L), r.suspendedReason(), r.suspendedAt())).toList();
    }

    private Map<Long, Long> counts(String sql, List<Long> ids) {
        Map<Long, Long> out = new HashMap<>();
        jdbc.sql(sql).param("ids", ids).query((rs, i) -> out.put(rs.getLong(1), rs.getLong(2))).list();
        return out;
    }

    /** Newest first; {@code q} matches a username or email prefix, case-insensitively. */
    @Transactional(readOnly = true)
    public CursorPage<AdminUser> users(AuthUser admin, String q, String status, String cursor, int limit) {
        requireAdmin(admin);
        Cursors.Key key = Cursors.decode(cursor);
        String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        String st = status == null || status.isBlank() ? "" : status.toUpperCase(Locale.ROOT);
        List<UserRow> rows = jdbc.sql("SELECT " + USER_COLS + """

                         FROM users u
                        WHERE (:q = '' OR lower(u.username) LIKE :prefix ESCAPE '\\' OR lower(u.email) LIKE :prefix ESCAPE '\\')
                          AND (:st = '' OR u.status = :st OR (:st = 'ADMIN' AND u.role = 'ADMIN'))
                          AND (:hasCursor = FALSE OR u.id < :cid)
                        ORDER BY u.id DESC LIMIT :lim""")
                .param("q", query).param("prefix", like(query) + "%").param("st", st)
                .param("hasCursor", key != null).param("cid", key == null ? 0L : key.id()).param("lim", limit + 1)
                .query(this::userRow).list();
        boolean more = rows.size() > limit;
        List<UserRow> page = more ? rows.subList(0, limit) : rows;
        return new CursorPage<>(toAdminUsers(page), more ? Cursors.encode(0, page.getLast().id()) : null);
    }

    @Transactional(readOnly = true)
    public AdminUserDetail user(AuthUser admin, long id) {
        requireAdmin(admin);
        AdminUser u = toAdminUsers(List.of(loadUser(id))).getFirst();
        List<CommunityRef> modOf = jdbc.sql("""
                        SELECT c.id, c.slug, c.name, c.icon_url FROM community_members m JOIN communities c ON c.id = m.community_id
                        WHERE m.user_id = ? AND m.role IN ('MODERATOR', 'ADMIN', 'OWNER') ORDER BY c.slug""")
                .param(id).query((rs, i) -> new CommunityRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        List<AuditRow> history = jdbc.sql(AUDIT_SELECT + " WHERE a.target_user_id = ? ORDER BY a.id DESC LIMIT 50")
                .param(id).query(this::auditRow).list();
        return new AdminUserDetail(u, modOf, toAudit(history));
    }

    private UserRow loadUser(long id) {
        return jdbc.sql("SELECT " + USER_COLS + " FROM users u WHERE u.id = ?").param(id).query(this::userRow)
                .optional().orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Suspension blocks sign-in and refresh, revokes every session and cuts off live access tokens (see AccountStatusCache). */
    @Transactional
    public AdminUserDetail suspend(AuthUser admin, long id, String reason) {
        requireAdmin(admin);
        if (id == admin.id()) throw ApiException.badRequest("You can't suspend yourself");
        UserRow u = loadUser(id);
        if ("DELETED".equals(u.status())) throw ApiException.badRequest("This account was deleted");
        if ("SUSPENDED".equals(u.status())) throw ApiException.badRequest("This account is already suspended");
        if ("ADMIN".equals(u.role())) throw ApiException.badRequest("Remove admin rights before suspending an admin");
        jdbc.sql("UPDATE users SET status = 'SUSPENDED', suspended_reason = ?, suspended_at = now(), updated_at = now() WHERE id = ?")
                .params(reason.strip(), id).update();
        refreshTokens.revokeAllForUser(id, Instant.now());
        accounts.evict(id);
        events.publishEvent(new AccountAccessRevoked(id, "suspended")); // closes open WebSockets after commit (D-030)
        audit(admin, "SUSPEND_USER", id, null, null, reason);
        return user(admin, id);
    }

    @Transactional
    public AdminUserDetail unsuspend(AuthUser admin, long id, String reason) {
        requireAdmin(admin);
        UserRow u = loadUser(id);
        if (!"SUSPENDED".equals(u.status())) throw ApiException.badRequest("This account isn't suspended");
        jdbc.sql("UPDATE users SET status = 'ACTIVE', suspended_reason = NULL, suspended_at = NULL, updated_at = now() WHERE id = ?")
                .param(id).update();
        accounts.evict(id);
        audit(admin, "UNSUSPEND_USER", id, null, null, reason);
        return user(admin, id);
    }

    @Transactional
    public AdminUserDetail setRole(AuthUser admin, long id, String role, String reason) {
        requireAdmin(admin);
        if (id == admin.id()) throw ApiException.badRequest("You can't change your own admin rights");
        UserRow u = loadUser(id);
        if (!"ACTIVE".equals(u.status())) throw ApiException.badRequest("Only active accounts can be promoted or demoted");
        if (role.equals(u.role())) throw ApiException.badRequest("ADMIN".equals(role) ? "Already an admin" : "Not an admin");
        jdbc.sql("UPDATE users SET role = ?, updated_at = now() WHERE id = ?").params(role, id).update();
        accounts.evict(id);
        // Sockets carry the role they connected with; closing them makes the client reconnect with the new one.
        events.publishEvent(new AccountAccessRevoked(id, "role changed"));
        audit(admin, "ADMIN".equals(role) ? "PROMOTE_ADMIN" : "DEMOTE_ADMIN", id, null, null, reason);
        return user(admin, id);
    }

    // ------------------------------------------------------------------ communities

    private record CommunityRow(long id, String slug, String name, String icon, String visibility, String status, String reason,
                                int members, int posts, Instant createdAt, Long ownerId) {}

    @Transactional(readOnly = true)
    public CursorPage<AdminCommunity> communities(AuthUser admin, String q, String status, String cursor, int limit) {
        requireAdmin(admin);
        Cursors.Key key = Cursors.decode(cursor);
        String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        String st = status == null || status.isBlank() ? "" : status.toUpperCase(Locale.ROOT);
        List<CommunityRow> rows = jdbc.sql("""
                        SELECT c.id, c.slug, c.name, c.icon_url, c.visibility, c.status, c.status_reason, c.member_count,
                               c.post_count, c.created_at,
                               (SELECT m.user_id FROM community_members m WHERE m.community_id = c.id AND m.role = 'OWNER' LIMIT 1) AS owner
                        FROM communities c
                        WHERE (:q = '' OR lower(c.slug) LIKE :contains ESCAPE '\\' OR lower(c.name) LIKE :contains ESCAPE '\\')
                          AND (:st = '' OR c.status = :st)
                          AND (:hasCursor = FALSE OR c.id < :cid)
                        ORDER BY c.id DESC LIMIT :lim""")
                .param("q", query).param("contains", "%" + like(query) + "%").param("st", st)
                .param("hasCursor", key != null).param("cid", key == null ? 0L : key.id()).param("lim", limit + 1)
                .query((rs, i) -> new CommunityRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9), ts(rs, "created_at"),
                        (Long) rs.getObject(11)))
                .list();
        boolean more = rows.size() > limit;
        List<CommunityRow> page = more ? rows.subList(0, limit) : rows;
        return new CursorPage<>(toAdminCommunities(page), more ? Cursors.encode(0, page.getLast().id()) : null);
    }

    private List<AdminCommunity> toAdminCommunities(List<CommunityRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(CommunityRow::id).toList();
        Map<Long, Long> reports = counts("""
                SELECT community_id, count(DISTINCT (post_id, coalesce(comment_id, 0))) FROM reports
                WHERE community_id IN (:ids) AND status = 'OPEN' GROUP BY community_id""", ids);
        Map<Long, Long> flags = counts("SELECT community_id, count(*) FROM spam_flags WHERE community_id IN (:ids) AND status = 'OPEN' GROUP BY community_id", ids);
        Map<Long, UserSummary> owners = directory.summaries(rows.stream().map(CommunityRow::ownerId).toList());
        return rows.stream().map(r -> new AdminCommunity(r.id(), r.slug(), r.name(), r.icon(), r.visibility(), r.status(),
                r.reason(), r.members(), r.posts(), r.createdAt(), r.ownerId() == null ? null : owners.get(r.ownerId()),
                reports.getOrDefault(r.id(), 0L), flags.getOrDefault(r.id(), 0L))).toList();
    }

    /** Quarantine, ban or restore. Moderators of the community are told, with the reason. */
    @Transactional
    public AdminCommunity setCommunityStatus(AuthUser admin, String slug, String status, String reason) {
        requireAdmin(admin);
        CommunityRow c = jdbc.sql("""
                        SELECT c.id, c.slug, c.name, c.icon_url, c.visibility, c.status, c.status_reason, c.member_count,
                               c.post_count, c.created_at, NULL::bigint FROM communities c WHERE lower(c.slug) = lower(?)""")
                .param(slug)
                .query((rs, i) -> new CommunityRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9), ts(rs, "created_at"), null))
                .optional().orElseThrow(() -> ApiException.notFound("Community"));
        if (status.equals(c.status())) throw ApiException.badRequest("The community is already " + status.toLowerCase(Locale.ROOT));
        boolean restoring = "ACTIVE".equals(status);
        if (!restoring && (reason == null || reason.isBlank())) throw ApiException.field("reason", "Give a reason; moderators will see it");
        String clean = reason == null || reason.isBlank() ? null : reason.strip();
        jdbc.sql("UPDATE communities SET status = ?, status_reason = ?, updated_at = now() WHERE id = ?")
                .params(status, restoring ? null : clean, c.id()).update();
        String action = switch (status) {
            case "QUARANTINED" -> "QUARANTINE_COMMUNITY";
            case "BANNED" -> "BAN_COMMUNITY";
            default -> "RESTORE_COMMUNITY";
        };
        audit(admin, action, null, c.id(), null, clean);
        List<Long> mods = jdbc.sql("SELECT user_id FROM community_members WHERE community_id = ? AND role IN ('MODERATOR', 'ADMIN', 'OWNER')")
                .param(c.id()).query(Long.class).list();
        for (Long m : mods) notifications.communityStatus(m, c.slug(), status, clean);
        return communities(admin, c.slug(), null, null, 50).items().stream().filter(x -> x.id() == c.id()).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ flags and reports

    @Transactional(readOnly = true)
    public CursorPage<SpamService.SpamFlagDto> flags(AuthUser admin, String status, String cursor, int limit) {
        requireAdmin(admin);
        return spam.siteFlags(!"ALL".equalsIgnoreCase(status), cursor, limit);
    }

    @Transactional
    public Resolution resolveFlag(AuthUser admin, long flagId, boolean approve) {
        requireAdmin(admin);
        Resolution r = spam.resolve(flagId, admin, approve);
        audit(admin, approve ? "APPROVE_FLAG" : "REMOVE_FLAG", null, r.communityId(), flagId, null);
        return r;
    }

    /** Open reports across every community, grouped per target, most-reported first. Admins act through the mod endpoints. */
    @Transactional(readOnly = true)
    public List<AdminReport> reports(AuthUser admin) {
        requireAdmin(admin);
        record G(long cid, String slug, String name, String icon, String type, long postId, Long commentId, long n,
                 List<String> reasons, Instant last) {}
        List<G> groups = jdbc.sql("""
                        SELECT r.community_id, c.slug, c.name, c.icon_url, r.target_type, r.post_id, r.comment_id, count(*) AS n,
                               array_agg(DISTINCT r.reason) AS reasons, max(r.created_at) AS last
                        FROM reports r JOIN communities c ON c.id = r.community_id
                        WHERE r.status = 'OPEN'
                        GROUP BY r.community_id, c.slug, c.name, c.icon_url, r.target_type, r.post_id, r.comment_id
                        ORDER BY n DESC, last DESC LIMIT 100""")
                .query((rs, i) -> new G(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6), (Long) rs.getObject(7), rs.getLong(8), textArray(rs.getArray(9)), ts(rs, "last")))
                .list();
        if (groups.isEmpty()) return List.of();
        record P(String title, String body, Long author, boolean removed) {}
        Map<Long, P> posts = new HashMap<>();
        jdbc.sql("SELECT id, title, body_text, author_id, removed FROM posts WHERE id IN (:ids)")
                .param("ids", groups.stream().map(G::postId).distinct().toList())
                .query((rs, i) -> posts.put(rs.getLong(1), new P(rs.getString(2), rs.getString(3), (Long) rs.getObject(4), rs.getBoolean(5))))
                .list();
        Map<Long, P> comments = new HashMap<>();
        List<Long> commentIds = groups.stream().map(G::commentId).filter(Objects::nonNull).distinct().toList();
        if (!commentIds.isEmpty()) {
            jdbc.sql("SELECT id, body, author_id, removed FROM comments WHERE id IN (:ids)").param("ids", commentIds)
                    .query((rs, i) -> comments.put(rs.getLong(1), new P(null, rs.getString(2), (Long) rs.getObject(3), rs.getBoolean(4))))
                    .list();
        }
        Map<Long, UserSummary> authors = directory.summaries(Stream.concat(posts.values().stream(), comments.values().stream())
                .map(P::author).toList());
        return groups.stream().filter(g -> posts.containsKey(g.postId())).map(g -> {
            P post = posts.get(g.postId());
            P target = g.commentId() == null ? post : comments.get(g.commentId());
            if (target == null) target = post;
            return new AdminReport(new CommunityRef(g.cid(), g.slug(), g.name(), g.icon()), g.type(), g.postId(), g.commentId(),
                    post.title(), TextSignals.excerpt(target.body() == null ? "" : target.body(), 240),
                    target.author() == null ? null : authors.get(target.author()), g.n(), g.reasons(), target.removed(), g.last());
        }).toList();
    }

    // ------------------------------------------------------------------ audit log

    private static final String AUDIT_SELECT = """
            SELECT a.id, a.action, a.admin_id, a.target_user_id, a.target_community_id, c.slug, c.name, c.icon_url,
                   a.flag_id, a.reason, a.created_at
            FROM admin_actions a LEFT JOIN communities c ON c.id = a.target_community_id""";

    private record AuditRow(long id, String action, Long adminId, Long targetUserId, Long communityId, String slug, String name,
                            String icon, Long flagId, String reason, Instant at) {}

    private AuditRow auditRow(ResultSet rs, int i) throws SQLException {
        return new AuditRow(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), (Long) rs.getObject(4), (Long) rs.getObject(5),
                rs.getString(6), rs.getString(7), rs.getString(8), (Long) rs.getObject(9), rs.getString(10), ts(rs, "created_at"));
    }

    private List<AuditEntry> toAudit(List<AuditRow> rows) {
        Map<Long, UserSummary> u = directory.summaries(rows.stream().flatMap(r -> Stream.of(r.adminId(), r.targetUserId())).toList());
        return rows.stream().map(r -> new AuditEntry(r.id(), r.action(), r.adminId() == null ? null : u.get(r.adminId()),
                r.targetUserId() == null ? null : u.get(r.targetUserId()),
                r.communityId() == null ? null : new CommunityRef(r.communityId(), r.slug(), r.name(), r.icon()),
                r.flagId(), r.reason(), r.at())).toList();
    }

    @Transactional(readOnly = true)
    public CursorPage<AuditEntry> audit(AuthUser admin, String cursor, int limit) {
        requireAdmin(admin);
        Cursors.Key key = Cursors.decode(cursor);
        List<AuditRow> rows = jdbc.sql(AUDIT_SELECT + """

                         WHERE (:hasCursor = FALSE OR a.id < :cid) ORDER BY a.id DESC LIMIT :lim""")
                .param("hasCursor", key != null).param("cid", key == null ? 0L : key.id()).param("lim", limit + 1)
                .query(this::auditRow).list();
        boolean more = rows.size() > limit;
        List<AuditRow> page = more ? rows.subList(0, limit) : rows;
        return new CursorPage<>(toAudit(page), more ? Cursors.encode(0, page.getLast().id()) : null);
    }

    private void audit(AuthUser admin, String action, Long targetUser, Long community, Long flagId, String reason) {
        jdbc.sql("""
                        INSERT INTO admin_actions (admin_id, action, target_user_id, target_community_id, flag_id, reason)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(admin.id(), action, targetUser, community, flagId, reason == null || reason.isBlank() ? null : reason.strip())
                .update();
    }

    // ------------------------------------------------------------------ helpers

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static List<String> textArray(Array a) throws SQLException {
        if (a == null) return List.of();
        return Arrays.stream((Object[]) a.getArray()).map(Objects::toString).toList();
    }
}

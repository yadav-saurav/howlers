package app.howlers.notification;

import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications. Similar unread events share a {@code group_key} and bump a counter instead of
 * adding rows ("3 new replies on …"), which keeps busy threads from flooding the inbox.
 * Delivery: the inbox is pulled over REST; a NOTIFICATION event over WebSocket tells open clients to refetch.
 */
@Service
public class NotificationService {
    private final JdbcClient jdbc;
    private final UserDirectory userDirectory;
    private final app.howlers.realtime.RealtimePublisher realtime;

    public NotificationService(JdbcClient jdbc, UserDirectory userDirectory, app.howlers.realtime.RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
        this.userDirectory = userDirectory;
    }

    /** Web push (D-040): optional so the inbox works the same without it. */
    private app.howlers.push.PushNotifier pushNotifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPushNotifier(app.howlers.push.PushNotifier pushNotifier) {
        this.pushNotifier = pushNotifier;
    }

    public record NotificationDto(long id, String type, UserSummary actor, String message, String link, boolean read,
                                  Instant createdAt, int count) {}

    private record Row(long id, String type, Long actorId, String message, String link, boolean read,
                       Instant createdAt, int count) {}

    // ------------------------------------------------------------------ producers

    public void postReply(long recipientId, long actorId, String actorName, long postId, long commentId, String postTitle) {
        push(recipientId, "POST_REPLY", actorId, "post-reply:" + postId,
                "u/" + actorName + " commented on your post “" + clip(postTitle) + "”", "/p/" + postId + "#c-" + commentId);
    }

    public void commentReply(long recipientId, long actorId, String actorName, long postId, long commentId, long parentId) {
        push(recipientId, "COMMENT_REPLY", actorId, "comment-reply:" + parentId,
                "u/" + actorName + " replied to your comment", "/p/" + postId + "#c-" + commentId);
    }

    public void mention(long recipientId, long actorId, String actorName, long postId, Long commentId) {
        String link = commentId == null ? "/p/" + postId : "/p/" + postId + "#c-" + commentId;
        push(recipientId, "MENTION", actorId, null, "u/" + actorName + " mentioned you", link);
    }

    public void newFollower(long recipientId, long actorId, String actorName) {
        push(recipientId, "NEW_FOLLOWER", actorId, "followers", "u/" + actorName + " started following you", "/u/" + actorName);
    }

    public void postMilestone(long recipientId, long postId, String postTitle, int score) {
        push(recipientId, "POST_MILESTONE", null, null,
                "Your post “" + clip(postTitle) + "” reached " + score + " points", "/p/" + postId);
    }

    public void communityRole(long recipientId, long actorId, String slug, String role) {
        push(recipientId, "COMMUNITY_ROLE", actorId, null,
                "You are now a " + role.toLowerCase() + " of c/" + slug, "/c/" + slug);
    }

    /** Moderator identity is intentionally not attached (actor null): actions are taken on behalf of the team. */
    public void contentRemoved(long recipientId, String slug, String what, long actionId, String reason) {
        push(recipientId, "CONTENT_REMOVED", null, null,
                "Your " + what + " in c/" + slug + " was removed by moderators" + (reason == null || reason.isBlank() ? "" : ": " + clip(reason)),
                "/mod-actions/" + actionId);
    }

    public void banned(long recipientId, String slug, long actionId, String untilText) {
        push(recipientId, "BANNED", null, null, "You were banned from c/" + slug + " " + untilText, "/mod-actions/" + actionId);
    }

    public void appealResolved(long recipientId, String slug, boolean accepted, long actionId) {
        push(recipientId, "APPEAL_RESOLVED", null, null,
                "Your appeal in c/" + slug + " was " + (accepted ? "accepted and the action was reversed" : "reviewed and not accepted"),
                "/mod-actions/" + actionId);
    }

    /** Sent to a community's moderators when platform admins quarantine, ban or restore it (actor withheld, like removals). */
    public void communityStatus(long recipientId, String slug, String status, String reason) {
        String what = switch (status) {
            case "QUARANTINED" -> "was quarantined by the site admins";
            case "BANNED" -> "was banned by the site admins";
            default -> "was restored by the site admins";
        };
        push(recipientId, "COMMUNITY_STATUS", null, null,
                "c/" + slug + " " + what + (reason == null || reason.isBlank() ? "" : ": " + clip(reason)), "/c/" + slug);
    }

    public void pulseResult(long recipientId, String slug, long pulseId, String title, boolean correct) {
        push(recipientId, "PULSE_RESULT", null, "pulse:" + pulseId,
                "The prediction “" + clip(title) + "” in c/" + slug + " was resolved" + (correct ? ". You called it!" : ""),
                "/c/" + slug + "?view=pulse#pulse-" + pulseId);
    }

    /** Reactions to the same post or comment bump one unread notification (D-044). Blocked and muted people are skipped by push. */
    public void reaction(long recipientId, long actorId, String actorName, String emoji, boolean comment, long postId,
                         Long commentId, String postTitle) {
        String link = commentId == null ? "/p/" + postId : "/p/" + postId + "#c-" + commentId;
        push(recipientId, "REACTION", actorId, "reaction:" + (comment ? "c" + commentId : "p" + postId),
                "u/" + actorName + " reacted " + emoji + " to your " + (comment ? "comment" : "post “" + clip(postTitle) + "”"), link);
    }

    /** Automod held the author's content for moderator review (D-045). The reason is the rule's author-facing text. */
    public void automodHeld(long recipientId, String slug, String what, long postId, Long commentId, String reason) {
        String link = commentId == null ? "/p/" + postId : "/p/" + postId + "#c-" + commentId;
        push(recipientId, "AUTOMOD_HELD", null, null,
                "Your " + what + " in c/" + slug + " is waiting for moderator review" + (reason == null || reason.isBlank() ? "" : ": " + clip(reason)),
                link);
    }

    private void push(long recipientId, String type, Long actorId, String groupKey, String message, String link) {
        if (actorId != null && actorId == recipientId) return;
        // Blocked and muted people can't notify you (mutes: D-021).
        if (actorId != null && jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?)
                            OR EXISTS (SELECT 1 FROM user_mutes WHERE muter_id = ? AND muted_id = ?)""")
                .params(recipientId, actorId, recipientId, actorId).query(Boolean.class).single()) {
            return;
        }
        if (groupKey == null) {
            jdbc.sql("INSERT INTO notifications (user_id, type, actor_id, message, link) VALUES (?, ?, ?, ?, ?)")
                    .params(recipientId, type, actorId, message, link).update();
            announce(recipientId);
            if (pushNotifier != null) pushNotifier.notification(recipientId, type, null, message, link);
            return;
        }
        jdbc.sql("""
                INSERT INTO notifications (user_id, type, actor_id, group_key, message, link)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, group_key) WHERE read_at IS NULL AND group_key IS NOT NULL
                DO UPDATE SET count = notifications.count + 1, created_at = now(),
                              actor_id = EXCLUDED.actor_id, message = EXCLUDED.message, link = EXCLUDED.link""")
                .params(recipientId, type, actorId, groupKey, message, link).update();
        announce(recipientId);
        if (pushNotifier != null) pushNotifier.notification(recipientId, type, groupKey, message, link);
    }

    /** Live badge update; sent after commit, and harmless when the user has no open socket. */
    private void announce(long recipientId) {
        realtime.toUser(recipientId, "NOTIFICATION", java.util.Map.of("unreadCount", unreadCount(recipientId)));
    }

    private static String clip(String s) {
        return s.length() <= 60 ? s : s.substring(0, 59) + "…";
    }

    // ------------------------------------------------------------------ inbox

    @Transactional(readOnly = true)
    public CursorPage<NotificationDto> list(long userId, String cursor, int limit, boolean unreadOnly) {
        Cursors.Key key = Cursors.decode(cursor);
        List<Row> rows = jdbc.sql("""
                        SELECT id, type, actor_id, message, link, read_at IS NOT NULL AS read, created_at, count
                        FROM notifications
                        WHERE user_id = :u AND (:unread = FALSE OR read_at IS NULL)
                          AND (:hasCursor = FALSE OR (created_at, id) < (timestamptz 'epoch' + :ts * interval '1 microsecond', :cid))
                        ORDER BY created_at DESC, id DESC LIMIT :limit""")
                .param("u", userId).param("unread", unreadOnly)
                .param("hasCursor", key != null).param("ts", key == null ? 0L : (long) key.value())
                .param("cid", key == null ? 0L : key.id()).param("limit", limit + 1)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("type"),
                        (Long) rs.getObject("actor_id"), rs.getString("message"), rs.getString("link"),
                        rs.getBoolean("read"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("count")))
                .list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        Map<Long, UserSummary> actors = userDirectory.summaries(page.stream().map(Row::actorId).toList());
        List<NotificationDto> items = page.stream().map(r -> new NotificationDto(r.id(), r.type(),
                r.actorId() == null ? null : actors.get(r.actorId()), r.message(), r.link(), r.read(), r.createdAt(),
                r.count())).toList();
        String next = more ? Cursors.encode(Cursors.micros(page.getLast().createdAt()), page.getLast().id()) : null;
        return new CursorPage<>(items, next);
    }

    public long unreadCount(long userId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL")
                .param(userId).query(Long.class).single();
    }

    @Transactional
    public void markRead(long userId, long id) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE id = ? AND user_id = ? AND read_at IS NULL")
                .params(id, userId).update();
    }

    @Transactional
    public void markAllRead(long userId) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL").param(userId).update();
    }
}

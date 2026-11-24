package app.howlers.messaging;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.ConversationSummary;
import app.howlers.messaging.DmDtos.DmMedia;
import app.howlers.messaging.DmDtos.DmMessage;
import app.howlers.messaging.DmDtos.LastMessage;
import app.howlers.messaging.DmDtos.MessagePage;
import app.howlers.messaging.DmDtos.RoomInfo;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.realtime.PresenceTracker;
import app.howlers.realtime.RealtimePublisher;
import app.howlers.storage.StorageService;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct messages and pack rooms. A one-to-one conversation is keyed by the sorted pair of user ids, so "get or
 * create" is idempotent and race-free. A pack room (D-043) is a named conversation with 3 to 50 members whose
 * membership {@link RoomService} manages; reading, sending, receipts, typing and deletion share this code. Messages are
 * plain text (rendered as text, never HTML) plus up to four images the sender uploaded. Realtime events go out after
 * commit; REST stays the source of truth.
 */
@Service
public class DmService {
    private static final int MAX_BODY = 4000;
    static final String DIRECT = "DIRECT";
    static final String ROOM = "ROOM";

    private final JdbcClient jdbc;
    private final DmAccess access;
    private final UserDirectory users;
    private final StorageService storage;
    private final RateLimiter limiter;
    private final RealtimePublisher realtime;
    private final PresenceTracker presence;
    private final DmMediaUrls mediaUrls;
    private final app.howlers.common.metrics.HowlersMetrics metrics;
    private final LegacyDmMedia legacy;

    /** Web push (D-040): optional so messaging works the same without it. */
    private app.howlers.push.PushNotifier pushNotifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPushNotifier(app.howlers.push.PushNotifier pushNotifier) {
        this.pushNotifier = pushNotifier;
    }

    public DmService(JdbcClient jdbc, DmAccess access, UserDirectory users, StorageService storage, RateLimiter limiter,
                     RealtimePublisher realtime, PresenceTracker presence, DmMediaUrls mediaUrls,
                     app.howlers.common.metrics.HowlersMetrics metrics, LegacyDmMedia legacy) {
        this.legacy = legacy;
        this.metrics = metrics;
        this.mediaUrls = mediaUrls;
        this.jdbc = jdbc;
        this.access = access;
        this.users = users;
        this.storage = storage;
        this.limiter = limiter;
        this.realtime = realtime;
        this.presence = presence;
    }

    // ---------------------------------------------------------------- conversations

    @Transactional(readOnly = true)
    public List<ConversationSummary> list(AuthUser user, String q) {
        return summaries(user, 0, q == null ? "" : q.strip(), false);
    }

    @Transactional(readOnly = true)
    public ConversationSummary get(long id, AuthUser user) {
        return summaries(user, id, "", true).stream().findFirst().orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    @Transactional
    public ConversationSummary start(AuthUser user, String username) {
        Long otherId = jdbc.sql("SELECT id FROM users WHERE lower(username) = lower(?) AND deleted_at IS NULL AND status = 'ACTIVE'")
                .param(username.strip()).query(Long.class).optional().orElseThrow(() -> ApiException.notFound("User"));
        if (otherId == user.id()) throw ApiException.badRequest("You can't message yourself");
        String pair = DmAccess.pairKey(user.id(), otherId);
        Long existing = jdbc.sql("SELECT id FROM dm_conversations WHERE pair_key = ?").param(pair).query(Long.class).optional().orElse(null);
        if (existing == null) {
            String reason = access.cannotMessageReason(user.id(), otherId);
            if (reason != null) throw ApiException.forbidden(reason);
            jdbc.sql("INSERT INTO dm_conversations (pair_key) VALUES (?) ON CONFLICT (pair_key) DO NOTHING").param(pair).update();
            existing = jdbc.sql("SELECT id FROM dm_conversations WHERE pair_key = ?").param(pair).query(Long.class).single();
            jdbc.sql("INSERT INTO dm_participants (conversation_id, user_id) VALUES (?, ?), (?, ?) ON CONFLICT DO NOTHING")
                    .params(existing, user.id(), existing, otherId).update();
        }
        return get(existing, user);
    }

    private static String likePattern(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private List<ConversationSummary> summaries(AuthUser user, long onlyId, String q, boolean includeEmpty) {
        List<ConversationSummary> out = new ArrayList<>(directSummaries(user, onlyId, q, includeEmpty));
        out.addAll(roomSummaries(user, onlyId, q));
        out.sort(Comparator.comparing(ConversationSummary::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ConversationSummary::id, Comparator.reverseOrder()));
        return out.size() > 100 ? new ArrayList<>(out.subList(0, 100)) : out;
    }

    private List<ConversationSummary> directSummaries(AuthUser user, long onlyId, String q, boolean includeEmpty) {
        record Row(long id, long otherId, Long otherRead, Long lmId, Long lmSender, String lmBody, Instant lmAt,
                   boolean lmDeleted, int lmMedia, long unread, Instant updatedAt) {}
        List<Row> rows = jdbc.sql("""
                        SELECT c.id, other.user_id AS other_id, other.last_read_message_id AS other_read,
                               lm.id AS lm_id, lm.sender_id AS lm_sender, lm.body AS lm_body, lm.created_at AS lm_at,
                               lm.deleted_at IS NOT NULL AS lm_deleted,
                               (SELECT count(*) FROM dm_message_media x WHERE x.message_id = lm.id) AS lm_media,
                               (SELECT count(*) FROM dm_messages m
                                 WHERE m.conversation_id = c.id AND m.id > COALESCE(me.last_read_message_id, 0)
                                   AND m.sender_id IS DISTINCT FROM me.user_id AND m.deleted_at IS NULL) AS unread,
                               COALESCE(c.last_message_at, c.created_at) AS updated_at
                        FROM dm_participants me
                        JOIN dm_conversations c ON c.id = me.conversation_id AND c.kind = 'DIRECT'
                        JOIN dm_participants other ON other.conversation_id = c.id AND other.user_id <> me.user_id
                        JOIN users ou ON ou.id = other.user_id
                        LEFT JOIN LATERAL (SELECT id, sender_id, body, created_at, deleted_at FROM dm_messages
                                           WHERE conversation_id = c.id ORDER BY id DESC LIMIT 1) lm ON TRUE
                        WHERE me.user_id = :me
                          AND (:onlyId = 0 OR c.id = :onlyId)
                          AND (:includeEmpty OR c.last_message_at IS NOT NULL)
                          AND (:q = '' OR ou.username ILIKE :pattern OR ou.display_name ILIKE :pattern
                               OR EXISTS (SELECT 1 FROM dm_messages mm WHERE mm.conversation_id = c.id
                                          AND mm.deleted_at IS NULL AND mm.body ILIKE :pattern))
                        ORDER BY updated_at DESC, c.id DESC
                        LIMIT 100""")
                .param("me", user.id()).param("onlyId", onlyId).param("includeEmpty", includeEmpty)
                .param("q", q).param("pattern", likePattern(q))
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getLong("other_id"), (Long) rs.getObject("other_read"),
                        (Long) rs.getObject("lm_id"), (Long) rs.getObject("lm_sender"), rs.getString("lm_body"),
                        instant(rs.getObject("lm_at", OffsetDateTime.class)), rs.getBoolean("lm_deleted"),
                        rs.getInt("lm_media"), rs.getLong("unread"),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .list();
        Map<Long, UserSummary> others = users.summaries(rows.stream().map(Row::otherId).toList());
        List<ConversationSummary> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            LastMessage last = r.lmId() == null ? null : new LastMessage(r.lmId(), r.lmSender(),
                    excerpt(r.lmBody(), r.lmDeleted(), r.lmMedia()), r.lmAt(), r.lmDeleted());
            String reason = access.cannotMessageReason(user.id(), r.otherId());
            out.add(new ConversationSummary(r.id(), others.get(r.otherId()), last, r.unread(), r.otherRead(),
                    presence.isOnline(r.otherId()), reason == null, reason, r.updatedAt(), DIRECT, null));
        }
        return out;
    }

    private List<ConversationSummary> roomSummaries(AuthUser user, long onlyId, String q) {
        record Row(long id, String name, String icon, String role, int members, Long lmId, Long lmSender, String lmKind,
                   String lmBody, Instant lmAt, boolean lmDeleted, boolean lmBlocked, int lmMedia, long unread,
                   Instant updatedAt) {}
        List<Row> rows = jdbc.sql("""
                        SELECT c.id, c.name, c.icon_emoji, me.role,
                               (SELECT count(*) FROM dm_participants x WHERE x.conversation_id = c.id) AS members,
                               lm.id AS lm_id, lm.sender_id AS lm_sender, lm.kind AS lm_kind, lm.body AS lm_body,
                               lm.created_at AS lm_at, lm.deleted_at IS NOT NULL AS lm_deleted,
                               (lm.kind = 'USER' AND EXISTS (SELECT 1 FROM user_blocks b
                                    WHERE b.blocker_id = me.user_id AND b.blocked_id = lm.sender_id)) AS lm_blocked,
                               (SELECT count(*) FROM dm_message_media x WHERE x.message_id = lm.id) AS lm_media,
                               (SELECT count(*) FROM dm_messages m
                                 WHERE m.conversation_id = c.id AND m.id > COALESCE(me.last_read_message_id, 0)
                                   AND m.sender_id IS DISTINCT FROM me.user_id AND m.deleted_at IS NULL AND m.kind = 'USER'
                                   AND NOT EXISTS (SELECT 1 FROM user_blocks b
                                                   WHERE b.blocker_id = me.user_id AND b.blocked_id = m.sender_id)) AS unread,
                               COALESCE(c.last_message_at, c.created_at) AS updated_at
                        FROM dm_participants me
                        JOIN dm_conversations c ON c.id = me.conversation_id AND c.kind = 'ROOM'
                        LEFT JOIN LATERAL (SELECT id, sender_id, kind, body, created_at, deleted_at FROM dm_messages
                                           WHERE conversation_id = c.id ORDER BY id DESC LIMIT 1) lm ON TRUE
                        WHERE me.user_id = :me
                          AND (:onlyId = 0 OR c.id = :onlyId)
                          AND (:q = '' OR c.name ILIKE :pattern
                               OR EXISTS (SELECT 1 FROM dm_messages mm WHERE mm.conversation_id = c.id
                                          AND mm.deleted_at IS NULL AND mm.kind = 'USER' AND mm.body ILIKE :pattern
                                          AND NOT EXISTS (SELECT 1 FROM user_blocks b
                                                          WHERE b.blocker_id = me.user_id AND b.blocked_id = mm.sender_id)))
                        ORDER BY updated_at DESC, c.id DESC
                        LIMIT 100""")
                .param("me", user.id()).param("onlyId", onlyId).param("q", q).param("pattern", likePattern(q))
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("name"), rs.getString("icon_emoji"),
                        rs.getString("role"), rs.getInt("members"), (Long) rs.getObject("lm_id"),
                        (Long) rs.getObject("lm_sender"), rs.getString("lm_kind"), rs.getString("lm_body"),
                        instant(rs.getObject("lm_at", OffsetDateTime.class)), rs.getBoolean("lm_deleted"),
                        rs.getBoolean("lm_blocked"), rs.getInt("lm_media"), rs.getLong("unread"),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .list();
        if (rows.isEmpty()) return List.of();
        Map<Long, List<Long>> members = new HashMap<>();
        jdbc.sql("SELECT conversation_id, user_id FROM dm_participants WHERE conversation_id IN (:ids)")
                .param("ids", rows.stream().map(Row::id).toList())
                .query((rs, i) -> members.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2)))
                .list();
        Map<Long, UserSummary> senders = users.summaries(rows.stream().map(Row::lmSender).toList());
        List<ConversationSummary> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            LastMessage last = null;
            if (r.lmId() != null) {
                boolean system = "SYSTEM".equals(r.lmKind());
                String text;
                if (system) text = r.lmBody();
                else if (r.lmBlocked()) text = "Message from someone you blocked";
                else {
                    text = excerpt(r.lmBody(), r.lmDeleted(), r.lmMedia());
                    UserSummary s = r.lmSender() == null ? null : senders.get(r.lmSender());
                    if (!r.lmDeleted() && r.lmSender() != null && r.lmSender() != user.id() && s != null) text = s.username() + ": " + text;
                }
                last = new LastMessage(r.lmId(), system ? null : r.lmSender(), text, r.lmAt(), r.lmDeleted());
            }
            boolean anyoneOnline = members.getOrDefault(r.id(), List.of()).stream()
                    .anyMatch(m -> m != user.id() && presence.isOnline(m));
            out.add(new ConversationSummary(r.id(), null, last, r.unread(), null, anyoneOnline, true, null, r.updatedAt(),
                    ROOM, new RoomInfo(r.name(), r.icon(), r.members(), r.role())));
        }
        return out;
    }

    private static String excerpt(String body, boolean deleted, int media) {
        if (deleted) return "Message deleted";
        if (body == null || body.isBlank()) return media == 1 ? "Sent an image" : "Sent " + media + " images";
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 119) + "…";
    }

    // ---------------------------------------------------------------- messages

    @Transactional(readOnly = true)
    public MessagePage messages(long conversationId, AuthUser user, Long before, int limit) {
        requireParticipant(conversationId, user.id());
        List<DmMessage> rows = jdbc.sql(SELECT_MESSAGE + """
                         WHERE m.conversation_id = :c AND (:before = 0 OR m.id < :before)
                        ORDER BY m.id DESC LIMIT :limit""")
                .param("viewer", user.id()).param("c", conversationId).param("before", before == null ? 0L : before)
                .param("limit", limit + 1)
                .query((rs, i) -> messageRow(rs))
                .list();
        boolean more = rows.size() > limit;
        List<DmMessage> page = new ArrayList<>(more ? rows.subList(0, limit) : rows);
        Collections.reverse(page);
        Map<Long, List<DmMedia>> media = mediaFor(page.stream().filter(m -> !m.deleted() && !m.fromBlocked())
                .map(DmMessage::id).toList(), user.id());
        List<DmMessage> items = page.stream().map(m -> withMedia(m, media.getOrDefault(m.id(), List.of()))).toList();
        return new MessagePage(items, more);
    }

    /**
     * {@code from_blocked} applies only to people's messages in pack rooms: one-to-one conversations with someone you
     * blocked are closed for writing instead (D-017), and their history stays readable as before.
     */
    private static final String SELECT_MESSAGE = """
            SELECT m.id, m.conversation_id, m.sender_id, m.body, m.created_at, m.deleted_at IS NOT NULL AS deleted, m.kind,
                   (c.kind = 'ROOM' AND m.kind = 'USER' AND EXISTS (SELECT 1 FROM user_blocks b
                        WHERE b.blocker_id = :viewer AND b.blocked_id = m.sender_id)) AS from_blocked
            FROM dm_messages m JOIN dm_conversations c ON c.id = m.conversation_id
            """;

    private static DmMessage messageRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean deleted = rs.getBoolean("deleted");
        boolean blocked = rs.getBoolean("from_blocked");
        return new DmMessage(rs.getLong("id"), rs.getLong("conversation_id"), (Long) rs.getObject("sender_id"),
                deleted || blocked ? "" : rs.getString("body"), List.of(),
                instant(rs.getObject("created_at", OffsetDateTime.class)), deleted, rs.getString("kind"), blocked);
    }

    @Transactional
    public DmMessage send(long conversationId, AuthUser user, SendRequest req) {
        String kind = requireParticipant(conversationId, user.id());
        Long otherId = null;
        if (DIRECT.equals(kind)) {
            otherId = otherParticipant(conversationId, user.id());
            String reason = access.cannotMessageReason(user.id(), otherId);
            if (reason != null) throw ApiException.forbidden(reason);
        }
        String body = clean(req.body());
        List<Long> mediaIds = req.mediaIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(req.mediaIds()));
        if (body.isEmpty() && mediaIds.isEmpty()) throw ApiException.field("body", "Write a message or attach an image");
        if (body.length() > MAX_BODY) throw ApiException.field("body", "Messages can be up to " + MAX_BODY + " characters");
        if (mediaIds.size() > 4) throw ApiException.field("mediaIds", "Attach up to 4 images");
        limiter.check("dm:" + user.id(), 60, Duration.ofMinutes(1));
        if (!mediaIds.isEmpty()) {
            long owned = jdbc.sql("SELECT count(*) FROM media_assets WHERE id IN (:ids) AND owner_id = :me AND content_type LIKE 'image/%'")
                    .param("ids", mediaIds).param("me", user.id()).query(Long.class).single();
            if (owned != mediaIds.size()) throw ApiException.field("mediaIds", "Attach images you uploaded");
        }
        long id = jdbc.sql("INSERT INTO dm_messages (conversation_id, sender_id, body) VALUES (?, ?, ?) RETURNING id")
                .params(conversationId, user.id(), body).query(Long.class).single();
        for (int i = 0; i < mediaIds.size(); i++) {
            jdbc.sql("INSERT INTO dm_message_media (message_id, media_id, position) VALUES (?, ?, ?)")
                    .params(id, mediaIds.get(i), i).update();
        }
        // The compatibility path accepts the sender's public uploads; move DM-only ones to private storage (D-030).
        legacy.privatize(mediaIds);
        jdbc.sql("UPDATE dm_conversations SET last_message_at = now() WHERE id = ?").param(conversationId).update();
        // Your own messages count as read by you.
        jdbc.sql("UPDATE dm_participants SET last_read_message_id = ? WHERE conversation_id = ? AND user_id = ?")
                .params(id, conversationId, user.id()).update();
        // Each participant gets their own copy: DM image URLs are signed for one viewer (D-024), and in a pack room a
        // member who blocked the sender gets the collapsed form.
        DmMessage msg = load(id, user.id());
        metrics.messageSent(!mediaIds.isEmpty());
        realtime.toUser(user.id(), "MESSAGE", msg);
        List<Long> recipients = otherId != null ? List.of(otherId)
                : memberIds(conversationId).stream().filter(m -> m != user.id()).toList();
        for (long r : recipients) {
            realtime.toUser(r, "MESSAGE", msg.media().isEmpty() && otherId != null ? msg : load(id, r));
            realtime.toUser(r, "UNREAD_MESSAGES", Map.of("count", unreadCount(r)));
            // Push skips recipients who blocked or muted the sender, or who turned message pushes off.
            if (pushNotifier != null) pushNotifier.directMessage(r, user.id(), user.username(), conversationId);
        }
        return msg;
    }

    @Transactional
    public void markRead(long conversationId, AuthUser user, long messageId) {
        String kind = requireParticipant(conversationId, user.id());
        boolean inConversation = jdbc.sql("SELECT EXISTS (SELECT 1 FROM dm_messages WHERE id = ? AND conversation_id = ?)")
                .params(messageId, conversationId).query(Boolean.class).single();
        if (!inConversation) throw ApiException.notFound("Message");
        int changed = jdbc.sql("""
                        UPDATE dm_participants SET last_read_message_id = ?
                        WHERE conversation_id = ? AND user_id = ? AND COALESCE(last_read_message_id, 0) < ?""")
                .params(messageId, conversationId, user.id(), messageId).update();
        if (changed == 0) return;
        Map<String, Object> receipt = Map.of("conversationId", conversationId, "userId", user.id(), "messageId", messageId);
        if (DIRECT.equals(kind)) realtime.toUser(otherParticipant(conversationId, user.id()), "READ", receipt);
        else realtime.toUsers(memberIds(conversationId).stream().filter(m -> m != user.id()).toList(), "READ", receipt);
        realtime.toUser(user.id(), "UNREAD_MESSAGES", Map.of("count", unreadCount(user.id())));
    }

    @Transactional
    public void delete(long messageId, AuthUser user) {
        Long conversationId = jdbc.sql("""
                        SELECT conversation_id FROM dm_messages
                        WHERE id = ? AND sender_id = ? AND deleted_at IS NULL AND kind = 'USER'""")
                .params(messageId, user.id()).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("Message"));
        // A member who was removed from a room can no longer touch what they wrote there.
        String kind = requireParticipant(conversationId, user.id());
        jdbc.sql("UPDATE dm_messages SET deleted_at = now(), body = '' WHERE id = ?").param(messageId).update();
        jdbc.sql("DELETE FROM dm_message_media WHERE message_id = ?").param(messageId).update();
        jdbc.sql("DELETE FROM reactions WHERE target_type = 'MESSAGE' AND target_id = ?").param(messageId).update();
        List<Long> others = DIRECT.equals(kind) ? List.of(otherParticipant(conversationId, user.id()))
                : memberIds(conversationId).stream().filter(m -> m != user.id()).toList();
        List<Long> everyone = new ArrayList<>(others);
        everyone.add(user.id());
        realtime.toUsers(everyone, "MESSAGE_DELETED", Map.of("conversationId", conversationId, "messageId", messageId));
        for (long o : others) realtime.toUser(o, "UNREAD_MESSAGES", Map.of("count", unreadCount(o)));
    }

    public long unreadCount(long userId) {
        return jdbc.sql("""
                        SELECT count(*) FROM dm_participants p
                        JOIN dm_conversations c ON c.id = p.conversation_id
                        JOIN dm_messages m ON m.conversation_id = p.conversation_id
                        WHERE p.user_id = ? AND m.id > COALESCE(p.last_read_message_id, 0)
                          AND m.sender_id IS DISTINCT FROM p.user_id AND m.deleted_at IS NULL AND m.kind = 'USER'
                          AND (c.kind = 'DIRECT' OR NOT EXISTS (SELECT 1 FROM user_blocks b
                                                                WHERE b.blocker_id = p.user_id AND b.blocked_id = m.sender_id))""")
                .param(userId).query(Long.class).single();
    }

    /**
     * Typing indicator: in a one-to-one conversation it is forwarded only while the two may still message each other;
     * in a pack room it goes to every other member except where a block exists in either direction.
     */
    public void typing(long conversationId, AuthUser user) {
        String kind = jdbc.sql("""
                        SELECT c.kind FROM dm_participants me JOIN dm_conversations c ON c.id = me.conversation_id
                        WHERE me.conversation_id = ? AND me.user_id = ?""")
                .params(conversationId, user.id()).query(String.class).optional().orElse(null);
        if (kind == null) return;
        Map<String, Object> payload = Map.of("conversationId", conversationId, "userId", user.id());
        if (DIRECT.equals(kind)) {
            long otherId = otherParticipant(conversationId, user.id());
            if (access.cannotMessageReason(user.id(), otherId) != null) return;
            realtime.toUser(otherId, "TYPING", payload);
            return;
        }
        List<Long> to = jdbc.sql("""
                        SELECT p.user_id FROM dm_participants p
                        WHERE p.conversation_id = :c AND p.user_id <> :me
                          AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE (b.blocker_id = p.user_id AND b.blocked_id = :me)
                                                                       OR (b.blocker_id = :me AND b.blocked_id = p.user_id))""")
                .param("c", conversationId).param("me", user.id()).query(Long.class).list();
        realtime.toUsers(to, "TYPING", payload);
    }

    // ---------------------------------------------------------------- shared with rooms and reactions

    /** The conversation's kind when {@code userId} is a participant; otherwise 404 (never confirms the id exists). */
    public String requireParticipant(long conversationId, long userId) {
        return jdbc.sql("""
                        SELECT c.kind FROM dm_participants p JOIN dm_conversations c ON c.id = p.conversation_id
                        WHERE p.conversation_id = ? AND p.user_id = ?""")
                .params(conversationId, userId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    public List<Long> memberIds(long conversationId) {
        return jdbc.sql("SELECT user_id FROM dm_participants WHERE conversation_id = ? ORDER BY joined_at, user_id")
                .param(conversationId).query(Long.class).list();
    }

    /** A message as one viewer sees it (their own signed media URLs, collapsed when they blocked the sender). */
    public DmMessage load(long id, long viewerId) {
        DmMessage m = jdbc.sql(SELECT_MESSAGE + " WHERE m.id = :id")
                .param("viewer", viewerId).param("id", id)
                .query((rs, i) -> messageRow(rs))
                .single();
        if (m.deleted() || m.fromBlocked()) return m;
        return withMedia(m, mediaFor(List.of(id), viewerId).getOrDefault(id, List.of()));
    }

    // ---------------------------------------------------------------- helpers

    private long otherParticipant(long conversationId, long userId) {
        requireParticipant(conversationId, userId);
        return jdbc.sql("SELECT user_id FROM dm_participants WHERE conversation_id = ? AND user_id <> ?")
                .params(conversationId, userId).query(Long.class).single();
    }

    private Map<Long, List<DmMedia>> mediaFor(List<Long> messageIds, long viewerId) {
        Map<Long, List<DmMedia>> out = new LinkedHashMap<>();
        if (messageIds.isEmpty()) return out;
        jdbc.sql("""
                        SELECT mm.message_id, a.id, a.storage_key, a.width, a.height, a.variant_widths
                        FROM dm_message_media mm JOIN media_assets a ON a.id = mm.media_id
                        WHERE mm.message_id IN (:ids) ORDER BY mm.message_id, mm.position""")
                .param("ids", messageIds)
                .query((rs, i) -> {
                    out.computeIfAbsent(rs.getLong("message_id"), k -> new ArrayList<>()).add(mediaUrls.dto(rs.getLong("id"),
                            rs.getString("storage_key"), (Integer) rs.getObject("width"), (Integer) rs.getObject("height"),
                            rs.getString("variant_widths"), viewerId));
                    return null;
                })
                .list();
        return out;
    }

    private static DmMessage withMedia(DmMessage m, List<DmMedia> media) {
        return new DmMessage(m.id(), m.conversationId(), m.senderId(), m.body(), media, m.createdAt(), m.deleted(),
                m.kind(), m.fromBlocked());
    }

    /** Plain text: normalise line endings, drop control characters (keeping newlines and tabs), trim. */
    static String clean(String body) {
        if (body == null) return "";
        return body.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").strip();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}

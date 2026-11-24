package app.howlers.messaging;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.ConversationSummary;
import app.howlers.messaging.DmDtos.RoomDetail;
import app.howlers.messaging.DmDtos.RoomMember;
import app.howlers.realtime.PresenceTracker;
import app.howlers.realtime.RealtimePublisher;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pack rooms (D-043): named group conversations. The creator is the OWNER; owners and ADMINs add and remove members
 * and rename the room; anyone can leave; only the owner promotes admins, hands over ownership or deletes the room.
 * Adding someone goes through the same rule as writing to them first ({@link DmAccess}: blocks in either direction,
 * then their {@code dmPolicy}), checked from the person who adds them. Every membership change is written to the
 * timeline as a SYSTEM message and announced to members after commit. A member who is removed or leaves loses the
 * room entirely (history, new messages and the signed image URLs, which re-check membership on every request).
 */
@Service
public class RoomService {
    public static final int MIN_MEMBERS = 3;
    public static final int MAX_MEMBERS = 50;

    private final JdbcClient jdbc;
    private final DmAccess access;
    private final DmService dms;
    private final UserDirectory users;
    private final RateLimiter limiter;
    private final RealtimePublisher realtime;
    private final PresenceTracker presence;

    public RoomService(JdbcClient jdbc, DmAccess access, DmService dms, UserDirectory users, RateLimiter limiter,
                       RealtimePublisher realtime, PresenceTracker presence) {
        this.jdbc = jdbc;
        this.access = access;
        this.dms = dms;
        this.users = users;
        this.limiter = limiter;
        this.realtime = realtime;
        this.presence = presence;
    }

    private record Target(long id, String username) {}

    // ---------------------------------------------------------------- create and read

    @Transactional
    public ConversationSummary create(AuthUser user, String rawName, String rawIcon, List<String> usernames) {
        limiter.check("room:create:" + user.id(), 10, Duration.ofHours(1));
        String name = name(rawName);
        String icon = icon(rawIcon);
        List<Target> invitees = resolveInvitees(user, usernames, List.of());
        if (invitees.size() + 1 < MIN_MEMBERS) {
            throw ApiException.field("usernames", "A pack room needs at least " + (MIN_MEMBERS - 1) + " other people");
        }
        if (invitees.size() + 1 > MAX_MEMBERS) throw ApiException.field("usernames", "A pack room holds up to " + MAX_MEMBERS + " members");
        long id = jdbc.sql("""
                        INSERT INTO dm_conversations (kind, name, icon_emoji, created_by, last_message_at)
                        VALUES ('ROOM', ?, ?, ?, now()) RETURNING id""")
                .params(name, icon, user.id()).query(Long.class).single();
        jdbc.sql("INSERT INTO dm_participants (conversation_id, user_id, role) VALUES (?, ?, 'OWNER')").params(id, user.id()).update();
        for (Target t : invitees) {
            jdbc.sql("INSERT INTO dm_participants (conversation_id, user_id, role) VALUES (?, ?, 'MEMBER')").params(id, t.id()).update();
        }
        long sys = system(id, user, "CREATED", null, user.username() + " created the room “" + name + "”");
        markOwnRead(id, user.id(), sys);
        announce(id, "ROOM_UPDATED");
        return dms.get(id, user);
    }

    @Transactional(readOnly = true)
    public RoomDetail detail(long id, AuthUser user) {
        requireRoomMember(id, user.id());
        record C(String name, String icon, Instant createdAt) {}
        C c = jdbc.sql("SELECT name, icon_emoji, created_at FROM dm_conversations WHERE id = ?").param(id)
                .query((rs, i) -> new C(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class).toInstant()))
                .single();
        record M(long userId, String role, Instant joined, Long read) {}
        List<M> rows = jdbc.sql("""
                        SELECT user_id, role, joined_at, last_read_message_id FROM dm_participants WHERE conversation_id = ?
                        ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, joined_at, user_id""")
                .param(id)
                .query((rs, i) -> new M(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class).toInstant(),
                        (Long) rs.getObject(4)))
                .list();
        Map<Long, UserSummary> u = users.summaries(rows.stream().map(M::userId).toList());
        List<RoomMember> members = new ArrayList<>();
        String mine = "MEMBER";
        for (M m : rows) {
            if (m.userId() == user.id()) mine = m.role();
            UserSummary s = u.get(m.userId());
            if (s == null) continue; // deleted accounts drop out of the list
            members.add(new RoomMember(s, m.role(), m.joined(), m.read(), presence.isOnline(m.userId())));
        }
        return new RoomDetail(id, c.name(), c.icon(), mine, members, MAX_MEMBERS, c.createdAt());
    }

    // ---------------------------------------------------------------- settings

    @Transactional
    public RoomDetail update(long id, AuthUser user, String rawName, String rawIcon) {
        String role = requireRoomMember(id, user.id());
        requireManager(role);
        record C(String name, String icon) {}
        C before = jdbc.sql("SELECT name, icon_emoji FROM dm_conversations WHERE id = ? FOR UPDATE").param(id)
                .query((rs, i) -> new C(rs.getString(1), rs.getString(2))).single();
        String name = rawName == null ? before.name() : name(rawName);
        String icon = rawIcon == null ? before.icon() : icon(rawIcon);
        if (Objects.equals(name, before.name()) && Objects.equals(icon, before.icon())) return detail(id, user);
        jdbc.sql("UPDATE dm_conversations SET name = ?, icon_emoji = ? WHERE id = ?").params(name, icon, id).update();
        if (!name.equals(before.name())) system(id, user, "RENAMED", null, user.username() + " renamed the room to “" + name + "”");
        else system(id, user, "ICON", null, user.username() + (icon == null ? " removed the room icon" : " changed the room icon to " + icon));
        announce(id, "ROOM_UPDATED");
        return detail(id, user);
    }

    // ---------------------------------------------------------------- membership

    @Transactional
    public RoomDetail addMembers(long id, AuthUser user, List<String> usernames) {
        String role = requireRoomMember(id, user.id());
        requireManager(role);
        limiter.check("room:add:" + user.id(), 100, Duration.ofHours(1));
        jdbc.sql("SELECT id FROM dm_conversations WHERE id = ? FOR UPDATE").param(id).query(Long.class).single();
        List<Long> current = dms.memberIds(id);
        List<Target> invitees = resolveInvitees(user, usernames, current);
        if (invitees.isEmpty()) throw ApiException.field("usernames", "Everyone you picked is already in this room");
        if (current.size() + invitees.size() > MAX_MEMBERS) {
            throw ApiException.field("usernames", "A pack room holds up to " + MAX_MEMBERS + " members (" + (MAX_MEMBERS - current.size()) + " places left)");
        }
        for (Target t : invitees) {
            // New members start at the current newest message: what was said before they joined is not "unread".
            jdbc.sql("""
                            INSERT INTO dm_participants (conversation_id, user_id, role, last_read_message_id)
                            VALUES (?, ?, 'MEMBER', (SELECT max(id) FROM dm_messages WHERE conversation_id = ?))""")
                    .params(id, t.id(), id).update();
            system(id, user, "ADDED", t.id(), user.username() + " added " + t.username());
        }
        announce(id, "ROOM_UPDATED");
        return detail(id, user);
    }

    /** Owner removes anyone but themselves; admins remove members only. */
    @Transactional
    public RoomDetail removeMember(long id, AuthUser user, long targetId) {
        String role = requireRoomMember(id, user.id());
        requireManager(role);
        if (targetId == user.id()) throw ApiException.badRequest("Use “Leave room” to leave");
        String targetRole = roleOf(id, targetId);
        if (targetRole == null) throw ApiException.notFound("Member");
        if ("OWNER".equals(targetRole)) throw ApiException.forbidden("The owner can't be removed");
        if ("ADMIN".equals(targetRole) && !"OWNER".equals(role)) throw ApiException.forbidden("Only the owner can remove an admin");
        String name = username(targetId);
        jdbc.sql("DELETE FROM dm_participants WHERE conversation_id = ? AND user_id = ?").params(id, targetId).update();
        system(id, user, "REMOVED", targetId, user.username() + " removed " + name);
        announce(id, "ROOM_UPDATED");
        realtime.toUser(targetId, "ROOM_REMOVED", Map.of("conversationId", id));
        realtime.toUser(targetId, "UNREAD_MESSAGES", Map.of("count", dms.unreadCount(targetId)));
        return detail(id, user);
    }

    @Transactional
    public RoomDetail setRole(long id, AuthUser user, long targetId, String rawRole) {
        String role = requireRoomMember(id, user.id());
        if (!"OWNER".equals(role)) throw ApiException.forbidden("Only the owner can change roles");
        String next = rawRole == null ? "" : rawRole.strip().toUpperCase(Locale.ROOT);
        if (!next.equals("ADMIN") && !next.equals("MEMBER")) {
            throw ApiException.field("role", "Choose ADMIN or MEMBER (use transfer to hand over ownership)");
        }
        String current = roleOf(id, targetId);
        if (current == null) throw ApiException.notFound("Member");
        if ("OWNER".equals(current)) throw ApiException.badRequest("Hand over ownership instead");
        if (current.equals(next)) return detail(id, user);
        jdbc.sql("UPDATE dm_participants SET role = ? WHERE conversation_id = ? AND user_id = ?").params(next, id, targetId).update();
        String name = username(targetId);
        system(id, user, next.equals("ADMIN") ? "PROMOTED" : "DEMOTED", targetId,
                user.username() + (next.equals("ADMIN") ? " made " + name + " an admin" : " made " + name + " a member"));
        announce(id, "ROOM_UPDATED");
        return detail(id, user);
    }

    /** The old owner stays as an admin. */
    @Transactional
    public RoomDetail transfer(long id, AuthUser user, long targetId) {
        String role = requireRoomMember(id, user.id());
        if (!"OWNER".equals(role)) throw ApiException.forbidden("Only the owner can hand over ownership");
        if (targetId == user.id()) throw ApiException.badRequest("You already own this room");
        if (roleOf(id, targetId) == null) throw ApiException.notFound("Member");
        // Demote first: the partial unique index allows only one OWNER row at a time.
        jdbc.sql("UPDATE dm_participants SET role = 'ADMIN' WHERE conversation_id = ? AND user_id = ?").params(id, user.id()).update();
        jdbc.sql("UPDATE dm_participants SET role = 'OWNER' WHERE conversation_id = ? AND user_id = ?").params(id, targetId).update();
        system(id, user, "OWNER", targetId, user.username() + " made " + username(targetId) + " the owner");
        announce(id, "ROOM_UPDATED");
        return detail(id, user);
    }

    /**
     * Anyone can leave. When the owner leaves, the longest-standing admin (or, without admins, member) becomes the
     * owner. The last person out deletes the room.
     */
    @Transactional
    public void leave(long id, AuthUser user) {
        String role = requireRoomMember(id, user.id());
        jdbc.sql("SELECT id FROM dm_conversations WHERE id = ? FOR UPDATE").param(id).query(Long.class).single();
        jdbc.sql("DELETE FROM dm_participants WHERE conversation_id = ? AND user_id = ?").params(id, user.id()).update();
        realtime.toUser(user.id(), "ROOM_REMOVED", Map.of("conversationId", id));
        realtime.toUser(user.id(), "UNREAD_MESSAGES", Map.of("count", dms.unreadCount(user.id())));
        List<Long> left = dms.memberIds(id);
        if (left.isEmpty()) {
            jdbc.sql("DELETE FROM dm_conversations WHERE id = ?").param(id).update();
            return;
        }
        system(id, user, "LEFT", null, user.username() + " left the room");
        if ("OWNER".equals(role)) {
            Long heir = jdbc.sql("""
                            SELECT user_id FROM dm_participants WHERE conversation_id = ?
                            ORDER BY CASE role WHEN 'ADMIN' THEN 0 ELSE 1 END, joined_at, user_id LIMIT 1""")
                    .param(id).query(Long.class).single();
            jdbc.sql("UPDATE dm_participants SET role = 'OWNER' WHERE conversation_id = ? AND user_id = ?").params(id, heir).update();
            system(id, user, "OWNER", heir, username(heir) + " is now the owner");
        }
        announce(id, "ROOM_UPDATED");
    }

    /** Owner only. Deletes the room and its whole history for everyone (images become unreachable at once). */
    @Transactional
    public void delete(long id, AuthUser user) {
        String role = requireRoomMember(id, user.id());
        if (!"OWNER".equals(role)) throw ApiException.forbidden("Only the owner can delete this room");
        List<Long> members = dms.memberIds(id);
        jdbc.sql("""
                        DELETE FROM reactions WHERE target_type = 'MESSAGE'
                          AND target_id IN (SELECT id FROM dm_messages WHERE conversation_id = ?)""").param(id).update();
        jdbc.sql("DELETE FROM dm_conversations WHERE id = ?").param(id).update();
        for (long m : members) {
            realtime.toUser(m, "ROOM_REMOVED", Map.of("conversationId", id));
            realtime.toUser(m, "UNREAD_MESSAGES", Map.of("count", dms.unreadCount(m)));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The caller's role; 404 for non-members and for one-to-one conversations (never confirms what the id is). */
    String requireRoomMember(long id, long userId) {
        return jdbc.sql("""
                        SELECT p.role FROM dm_participants p JOIN dm_conversations c ON c.id = p.conversation_id
                        WHERE p.conversation_id = ? AND p.user_id = ? AND c.kind = 'ROOM'""")
                .params(id, userId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Room"));
    }

    private static void requireManager(String role) {
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) throw ApiException.forbidden("Only the room's owner and admins can do that");
    }

    private String roleOf(long id, long userId) {
        return jdbc.sql("SELECT role FROM dm_participants WHERE conversation_id = ? AND user_id = ?").params(id, userId)
                .query(String.class).optional().orElse(null);
    }

    private String username(long userId) {
        return jdbc.sql("SELECT username FROM users WHERE id = ?").param(userId).query(String.class).optional().orElse("[deleted]");
    }

    /**
     * Resolves usernames to people who may be added by {@code user}: active accounts, not the caller, not already in,
     * and passing the same block and dmPolicy check as a first message. The first refusal names the person.
     */
    private List<Target> resolveInvitees(AuthUser user, List<String> usernames, List<Long> existing) {
        Map<String, Target> out = new LinkedHashMap<>();
        for (String raw : usernames == null ? List.<String>of() : usernames) {
            String uname = raw == null ? "" : raw.strip().replaceFirst("^u/", "");
            if (uname.isEmpty()) continue;
            Target t = jdbc.sql("SELECT id, username FROM users WHERE lower(username) = lower(?) AND deleted_at IS NULL AND status = 'ACTIVE'")
                    .param(uname).query((rs, i) -> new Target(rs.getLong(1), rs.getString(2))).optional()
                    .orElseThrow(() -> ApiException.field("usernames", "u/" + uname + " wasn't found"));
            if (t.id() == user.id() || existing.contains(t.id()) || out.containsKey(t.username().toLowerCase(Locale.ROOT))) continue;
            String reason = access.cannotMessageReason(user.id(), t.id());
            if (reason != null) throw ApiException.field("usernames", "u/" + t.username() + ": " + reason);
            out.put(t.username().toLowerCase(Locale.ROOT), t);
        }
        return new ArrayList<>(out.values());
    }

    private long system(long conversationId, AuthUser actor, String event, Long targetId, String text) {
        long id = jdbc.sql("""
                        INSERT INTO dm_messages (conversation_id, sender_id, body, kind, system_event, system_target_id)
                        VALUES (?, ?, ?, 'SYSTEM', ?, ?) RETURNING id""")
                .params(conversationId, actor.id(), clip(text, 300), event, targetId).query(Long.class).single();
        jdbc.sql("UPDATE dm_conversations SET last_message_at = now() WHERE id = ?").param(conversationId).update();
        for (long m : dms.memberIds(conversationId)) realtime.toUser(m, "MESSAGE", dms.load(id, m));
        return id;
    }

    private void markOwnRead(long conversationId, long userId, long messageId) {
        jdbc.sql("UPDATE dm_participants SET last_read_message_id = ? WHERE conversation_id = ? AND user_id = ?")
                .params(messageId, conversationId, userId).update();
    }

    private void announce(long id, String type) {
        realtime.toUsers(dms.memberIds(id), type, Map.of("conversationId", id));
    }

    static String name(String raw) {
        String n = raw == null ? "" : DmService.clean(raw).replaceAll("\\s+", " ");
        if (n.isEmpty()) throw ApiException.field("name", "Give the room a name");
        if (n.length() > 60) throw ApiException.field("name", "Room names can be up to 60 characters");
        return n;
    }

    /** A short emoji (no letters, digits or markup), or null to clear. */
    static String icon(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.isEmpty()) return null;
        if (s.codePointCount(0, s.length()) > 8 || s.length() > 16 || s.codePoints().anyMatch(cp ->
                Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) || cp < 0x2000 && cp != 0x00A9 && cp != 0x00AE)) {
            throw ApiException.field("iconEmoji", "Use a single emoji as the room icon");
        }
        return s;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

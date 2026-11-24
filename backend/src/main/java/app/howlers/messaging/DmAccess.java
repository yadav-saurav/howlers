package app.howlers.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Decides whether one user may message another. Blocks apply in both directions and always win. The recipient's
 * {@code dm_policy} applies too, except that you can always reply to someone who has already messaged you.
 */
@Component
public class DmAccess {
    private final JdbcClient jdbc;

    public DmAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String pairKey(long a, long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    /** {@code null} when {@code senderId} may message {@code recipientId}, otherwise a reason to show the sender. */
    public String cannotMessageReason(long senderId, long recipientId) {
        if (senderId == recipientId) return "You can't message yourself";
        record Facts(String status, boolean deleted, String policy, boolean iBlocked, boolean theyBlocked,
                     boolean theyFollowMe, boolean theyMessagedMe) {}
        Facts f = jdbc.sql("""
                        SELECT u.status, u.deleted_at IS NOT NULL AS deleted, u.dm_policy,
                          EXISTS (SELECT 1 FROM user_blocks WHERE blocker_id = :me AND blocked_id = :them) AS i_blocked,
                          EXISTS (SELECT 1 FROM user_blocks WHERE blocker_id = :them AND blocked_id = :me) AS they_blocked,
                          EXISTS (SELECT 1 FROM user_follows WHERE follower_id = :them AND followee_id = :me) AS they_follow,
                          EXISTS (SELECT 1 FROM dm_conversations c JOIN dm_messages m ON m.conversation_id = c.id
                                  WHERE c.pair_key = :pair AND m.sender_id = :them) AS they_messaged
                        FROM users u WHERE u.id = :them""")
                .param("me", senderId).param("them", recipientId).param("pair", pairKey(senderId, recipientId))
                .query((rs, i) -> new Facts(rs.getString("status"), rs.getBoolean("deleted"), rs.getString("dm_policy"),
                        rs.getBoolean("i_blocked"), rs.getBoolean("they_blocked"), rs.getBoolean("they_follow"),
                        rs.getBoolean("they_messaged")))
                .optional().orElse(null);
        if (f == null || f.deleted() || !"ACTIVE".equals(f.status())) return "This account isn't available";
        if (f.iBlocked()) return "You've blocked this user. Unblock them to send messages";
        if (f.theyBlocked()) return "You can't message this user";
        if (f.theyMessagedMe()) return null;
        return switch (f.policy()) {
            case "NOBODY" -> "This user isn't accepting messages";
            case "FOLLOWING" -> f.theyFollowMe() ? null : "This user only accepts messages from people they follow";
            default -> null;
        };
    }
}

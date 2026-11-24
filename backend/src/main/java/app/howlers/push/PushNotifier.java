package app.howlers.push;

import app.howlers.push.PushService.Category;
import app.howlers.push.PushService.PushMessage;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns in-app notifications and new direct messages into web pushes (D-040). Called from the producers inside their
 * transaction; the work is queued after commit (a rolled-back change never pushes) and runs on virtual threads, so a
 * slow push service never delays the request.
 *
 * <p>Batching: grouped notifications (several replies on one thread) push once per cooldown window and replace each
 * other on the device (same tag). Each user gets at most {@code maxPerUserPerMinute} pushes a minute; anything over
 * that is still in the in-app inbox. Both limits are kept in memory per instance, which is enough to stop floods.
 */
@Component
public class PushNotifier {
    private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);

    private final PushService push;
    private final PushProperties props;
    private final JdbcClient jdbc;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, long[]> perUser = new ConcurrentHashMap<>();
    private final Map<String, Long> groups = new ConcurrentHashMap<>();

    public PushNotifier(PushService push, PushProperties props, JdbcClient jdbc) {
        this.push = push;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** Category a notification type belongs to; null means it never pushes. */
    static Category category(String type) {
        return switch (type) {
            case "POST_REPLY", "COMMENT_REPLY" -> Category.REPLIES;
            case "MENTION" -> Category.MENTIONS;
            case "CONTENT_REMOVED", "BANNED", "APPEAL_RESOLVED", "COMMUNITY_STATUS", "COMMUNITY_ROLE" -> Category.MODERATION;
            case "NEW_FOLLOWER" -> Category.FOLLOWERS;
            case "POST_MILESTONE", "PULSE_RESULT" -> Category.ACTIVITY;
            default -> null;
        };
    }

    /** From {@code NotificationService} after it stored an in-app notification (blocks and mutes are already applied). */
    public void notification(long recipientId, String type, String groupKey, String message, String link) {
        Category c = category(type);
        if (c == null) return;
        String tag = groupKey != null ? groupKey : type.toLowerCase(java.util.Locale.ROOT) + ":" + link;
        enqueue(recipientId, c, groupKey, new PushMessage("Howlers", message, link, tag, type), "normal");
    }

    /**
     * From {@code DmService.send}. The message text is never included: the push says who wrote, nothing more
     * (messages can be private, and E2EE ones aren't readable by the server anyway).
     */
    public void directMessage(long recipientId, long senderId, String senderName, long conversationId) {
        if (isBlockedOrMuted(recipientId, senderId)) return;
        enqueue(recipientId, Category.MESSAGES, "dm:" + conversationId,
                new PushMessage("New message", "u/" + senderName + " sent you a message", "/messages/" + conversationId,
                        "dm:" + conversationId, "DIRECT_MESSAGE"), "high");
    }

    private boolean isBlockedOrMuted(long recipientId, long actorId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?)
                            OR EXISTS (SELECT 1 FROM user_mutes WHERE muter_id = ? AND muted_id = ?)""")
                .params(recipientId, actorId, recipientId, actorId).query(Boolean.class).single();
    }

    private void enqueue(long userId, Category category, String groupKey, PushMessage msg, String urgency) {
        if (push.vapid().isEmpty()) return;
        Runnable task = () -> executor.execute(() -> {
            try {
                send(userId, category, groupKey, msg, urgency);
            } catch (RuntimeException e) {
                log.warn("Web push for user {} failed: {}", userId, e.getClass().getSimpleName());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void send(long userId, Category category, String groupKey, PushMessage msg, String urgency) {
        var targets = push.targets(userId, category);
        if (targets.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (groupKey != null) {
            String key = userId + "|" + groupKey;
            Long last = groups.get(key);
            if (last != null && now - last < props.getGroupCooldown().toMillis()) return;
            groups.put(key, now);
        }
        if (!allow(userId, now)) return;
        for (var t : targets) push.deliver(t, msg, urgency);
        if (groups.size() > 50_000) groups.entrySet().removeIf(e -> now - e.getValue() > props.getGroupCooldown().toMillis());
    }

    private boolean allow(long userId, long now) {
        long window = Duration.ofMinutes(1).toMillis();
        long[] slot = perUser.compute(userId, (k, v) -> v == null || v[0] <= now ? new long[]{now + window, 0} : v);
        synchronized (slot) {
            if (perUser.size() > 50_000) perUser.entrySet().removeIf(e -> e.getValue()[0] <= now);
            return ++slot[1] <= props.getMaxPerUserPerMinute();
        }
    }

    /** Tests: forget rate-limit and cooldown state. */
    void reset() {
        perUser.clear();
        groups.clear();
    }
}

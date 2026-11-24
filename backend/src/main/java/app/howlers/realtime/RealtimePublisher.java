package app.howlers.realtime;

import java.util.Collection;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes small "something changed" events to connected clients. When called inside a transaction, the send is
 * deferred until after commit, so a client never refetches before the data it was told about is visible, and a
 * rolled-back change is never announced.
 */
@Component
public class RealtimePublisher {
    public static final String USER_QUEUE = "/queue/events";

    private final SimpMessagingTemplate template;

    public RealtimePublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    public record Event(String type, Object payload) {}

    public void toUser(long userId, String type, Object payload) {
        afterCommit(() -> template.convertAndSendToUser(Long.toString(userId), USER_QUEUE, new Event(type, payload)));
    }

    public void toUsers(Collection<Long> userIds, String type, Object payload) {
        for (Long id : userIds) toUser(id, type, payload);
    }

    public void pulseChanged(long pulseId, int totalVoters) {
        afterCommit(() -> template.convertAndSend("/topic/pulse." + pulseId, Map.of("pulseId", pulseId, "totalVoters", totalVoters)));
    }

    /** New post: always on the community topic; also on the site-wide topic when the community isn't private. */
    public void postCreated(long postId, long communityId, String communitySlug, boolean privateCommunity, long authorId) {
        Map<String, Object> body = Map.of("type", "POST_CREATED", "postId", postId, "communityId", communityId,
                "communitySlug", communitySlug, "authorId", authorId, "createdAt", java.time.Instant.now().toString());
        afterCommit(() -> {
            template.convertAndSend("/topic/community." + communityId, body);
            if (!privateCommunity) template.convertAndSend("/topic/posts", body);
        });
    }

    public void commentCreated(long postId, long commentId, Long parentId, long authorId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", "COMMENT_CREATED");
        body.put("postId", postId);
        body.put("commentId", commentId);
        body.put("parentId", parentId);
        body.put("authorId", authorId);
        afterCommit(() -> template.convertAndSend("/topic/post." + postId, body));
    }

    /** Reactions on a post or one of its comments changed (D-044). Ids only; clients refetch under the usual checks. */
    public void reactionsChanged(long postId, String targetType, long targetId) {
        Map<String, Object> body = Map.of("type", "REACTIONS", "postId", postId, "targetType", targetType, "targetId", targetId);
        afterCommit(() -> template.convertAndSend("/topic/post." + postId, body));
    }

    public void modQueueChanged(long communityId) {
        afterCommit(() -> template.convertAndSend("/topic/modqueue." + communityId, Map.of("communityId", communityId)));
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}

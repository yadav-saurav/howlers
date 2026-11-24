package app.howlers.realtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Batches score and comment-count changes into at most one frame per post every ~2 s. A busy thread with hundreds of
 * votes a second then costs one small query and one frame per interval instead of a frame per vote. Values are read
 * from the database at flush time, so they are always committed totals (a rolled-back vote just causes a no-op resend).
 */
@Component
public class LiveCounters {
    private static final int MAX_POSTS_PER_FLUSH = 500;

    private final JdbcClient jdbc;
    private final SimpMessagingTemplate template;
    private final Set<Long> dirtyPosts = ConcurrentHashMap.newKeySet();
    private final Map<Long, Set<Long>> dirtyComments = new ConcurrentHashMap<>();

    public LiveCounters(JdbcClient jdbc, SimpMessagingTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public void postChanged(long postId) {
        dirtyPosts.add(postId);
    }

    public void commentChanged(long postId, long commentId) {
        dirtyComments.computeIfAbsent(postId, k -> ConcurrentHashMap.newKeySet()).add(commentId);
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void flush() {
        List<Long> posts = drain(dirtyPosts);
        if (!posts.isEmpty()) {
            jdbc.sql("SELECT id, score, comment_count FROM posts WHERE id IN (:ids) AND deleted_at IS NULL")
                    .param("ids", posts)
                    .query((rs, i) -> {
                        long id = rs.getLong("id");
                        template.convertAndSend("/topic/post." + id, Map.of("type", "POST_STATS", "postId", id,
                                "score", rs.getInt("score"), "commentCount", rs.getInt("comment_count")));
                        return null;
                    })
                    .list();
        }
        for (Long postId : new ArrayList<>(dirtyComments.keySet())) {
            Set<Long> ids = dirtyComments.remove(postId);
            if (ids == null || ids.isEmpty()) continue;
            Map<String, Integer> scores = new LinkedHashMap<>();
            jdbc.sql("SELECT id, score FROM comments WHERE id IN (:ids) AND post_id = :p")
                    .param("ids", new ArrayList<>(ids)).param("p", postId)
                    .query((rs, i) -> scores.put(Long.toString(rs.getLong("id")), rs.getInt("score")))
                    .list();
            if (!scores.isEmpty()) {
                template.convertAndSend("/topic/post." + postId, Map.of("type", "COMMENT_SCORES", "postId", postId, "scores", scores));
            }
        }
    }

    private static List<Long> drain(Set<Long> set) {
        List<Long> out = new ArrayList<>();
        for (Long id : new HashSet<>(set)) {
            if (out.size() >= MAX_POSTS_PER_FLUSH) break; // the rest goes out on the next tick
            if (set.remove(id)) out.add(id);
        }
        return out;
    }
}

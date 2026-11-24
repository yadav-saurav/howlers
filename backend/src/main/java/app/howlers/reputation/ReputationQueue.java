package app.howlers.reputation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only reputation work done inside a vote or moderation transaction: one upsert that marks the content author
 * as needing a recompute. {@link ReputationJobs} picks dirty authors up a few seconds later, so vote latency is
 * unaffected by the reputation math.
 */
@Component
public class ReputationQueue {
    private final JdbcClient jdbc;

    public ReputationQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void markDirty(Long userId) {
        if (userId == null) return;
        jdbc.sql("INSERT INTO reputation_dirty (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING").param(userId).update();
    }
}

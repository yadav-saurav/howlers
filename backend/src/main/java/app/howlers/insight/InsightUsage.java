package app.howlers.insight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Records the token cost of every model call made for digests and stance suggestions (insight_llm_usage). */
@Component
public class InsightUsage {
    private static final Logger log = LoggerFactory.getLogger(InsightUsage.class);

    private final JdbcClient jdbc;

    public InsightUsage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String operation, Long postId, Long userId, String engine, String outcome, long in, long out) {
        try {
            jdbc.sql("""
                            INSERT INTO insight_llm_usage (operation, post_id, user_id, engine, outcome, input_tokens, output_tokens)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""")
                    .params(operation, postId, userId, engine, outcome, (int) in, (int) out).update();
        } catch (RuntimeException e) {
            log.warn("Could not record insight model usage ({})", e.getClass().getSimpleName());
        }
    }
}

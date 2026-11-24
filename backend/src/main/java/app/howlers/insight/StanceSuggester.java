package app.howlers.insight;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.insight.InsightDtos.AiStances;
import app.howlers.insight.ThreadLoader.Node;
import app.howlers.insight.ThreadLoader.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * AI-suggested stances for Context Mode (D-032). Only comments with no reader labels are sent, in batches with
 * structured output; results are stored in {@code comment_ai_stances}, separate from reader labels, keyed to the
 * comment's content hash so an edit (of the comment or the post) invalidates the suggestion.
 *
 * <p>A Context Mode view by a signed-in reader who can see the thread starts one background job per thread (it never
 * blocks the view); the response says PENDING and the client polls. Anonymous views read stored suggestions but never
 * start model calls. Jobs are rate-limited per user and a thread gets a short cooldown after each job.
 */
@Component
public class StanceSuggester {
    private static final Logger log = LoggerFactory.getLogger(StanceSuggester.class);

    static final int BATCH = 25;
    static final int MAX_PER_JOB = 75;
    static final Duration COOLDOWN = Duration.ofMinutes(2);
    static final int JOBS_PER_HOUR = 20;
    static final long MAX_OUTPUT_TOKENS = 8_000;

    private final InsightLlm llm;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RateLimiter limiter;
    private final InsightUsage usage;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "stance-suggester");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final Map<Long, Instant> lastRun = new ConcurrentHashMap<>();

    public StanceSuggester(InsightLlm llm, JdbcClient jdbc, ObjectMapper json, RateLimiter limiter, InsightUsage usage) {
        this.llm = llm;
        this.jdbc = jdbc;
        this.json = json;
        this.limiter = limiter;
        this.usage = usage;
    }

    /** Decides the AI-stance state for this view and starts a background job when one is due. */
    public AiStances request(long postId, Snapshot s, AuthUser viewer) {
        List<Node> nodes = s.nodes();
        int ai = (int) nodes.stream().filter(n -> n.stanceSource() == StanceSource.AI).count();
        if (!llm.available()) {
            return new AiStances("OFF", null, ai, 0, "No AI model is configured, so unlabelled comments use rule-based suggestions.");
        }
        List<Node> missing = nodes.stream().filter(n -> n.stanceSource() == StanceSource.RULES).toList();
        if (missing.isEmpty()) {
            return new AiStances("READY", llm.label(), ai, 0,
                    "Stances for unlabelled comments are suggested by " + llm.label() + ". Reader labels always take precedence.");
        }
        if (running.contains(postId)) return pending(ai, missing.size());
        if (viewer == null) {
            return new AiStances("PARTIAL", llm.label(), ai, missing.size(),
                    "Some unlabelled comments still use rule-based suggestions. Sign in to request AI suggestions.");
        }
        Instant last = lastRun.get(postId);
        if (last != null && last.isAfter(Instant.now().minus(COOLDOWN))) return partial(ai, missing.size());
        try {
            limiter.check("stance:ai:" + viewer.id(), JOBS_PER_HOUR, Duration.ofHours(1));
        } catch (ApiException e) {
            return partial(ai, missing.size());
        }
        if (!running.add(postId)) return pending(ai, missing.size());
        List<Node> job = missing.stream().limit(MAX_PER_JOB).toList();
        try {
            executor.submit(() -> {
                try {
                    suggest(postId, s.title(), s.postText(), nodes, job, viewer.id());
                } catch (RuntimeException e) {
                    log.warn("AI stance job for post {} failed ({})", postId, e.getClass().getSimpleName());
                } finally {
                    lastRun.put(postId, Instant.now());
                    running.remove(postId);
                }
            });
        } catch (RuntimeException e) {
            running.remove(postId);
            return partial(ai, missing.size());
        }
        return pending(ai, missing.size());
    }

    private AiStances pending(int ai, int remaining) {
        return new AiStances("PENDING", llm.label(), ai, remaining, "Asking " + llm.label() + " to suggest stances for "
                + remaining + " unlabelled " + (remaining == 1 ? "comment" : "comments") + "…");
    }

    private AiStances partial(int ai, int remaining) {
        return new AiStances("PARTIAL", llm.label(), ai, remaining,
                "Some unlabelled comments still use rule-based suggestions; AI suggestions will be requested again shortly.");
    }

    /** True while a job for this thread is running (tests poll this). */
    boolean isRunning(long postId) {
        return running.contains(postId);
    }

    /**
     * Runs the batches synchronously. Re-checks that every comment is still live right before sending it, so a comment
     * removed or deleted after the view never reaches the model. Returns the number of suggestions stored.
     */
    int suggest(long postId, String title, String postText, List<Node> all, List<Node> targets, Long userId) {
        Map<Long, String> parents = new HashMap<>();
        for (Node n : all) parents.put(n.id(), TextSignals.excerpt(n.body(), 300));
        int stored = 0;
        for (int i = 0; i < targets.size(); i += BATCH) {
            List<Node> batch = stillLive(targets.subList(i, Math.min(targets.size(), i + BATCH)));
            if (batch.isEmpty()) continue;
            InsightLlm.Completion out = llm.complete("stances", InsightPrompts.STANCE_SYSTEM,
                    InsightPrompts.stanceUser(title, postText, batch, parents), InsightPrompts.STANCE_SCHEMA, MAX_OUTPUT_TOKENS);
            Map<Long, Stance> labels = out.refused() ? Map.of() : validate(out.json(), batch, json);
            usage.record("STANCES", postId, userId, "llm-stance-v1", out.refused() ? "refused" : labels.isEmpty() ? "invalid" : "ok",
                    out.inputTokens(), out.outputTokens());
            for (Node n : batch) {
                Stance st = labels.get(n.id());
                if (st == null) continue;
                jdbc.sql("""
                                INSERT INTO comment_ai_stances (comment_id, content_hash, stance, engine) VALUES (?, ?, ?, ?)
                                ON CONFLICT (comment_id) DO UPDATE SET content_hash = EXCLUDED.content_hash,
                                  stance = EXCLUDED.stance, engine = EXCLUDED.engine, created_at = now()""")
                        .params(n.id(), n.contentHash(), st.name(), "llm-stance-v1").update();
                stored++;
            }
        }
        return stored;
    }

    private List<Node> stillLive(List<Node> batch) {
        if (batch.isEmpty()) return batch;
        Set<Long> live = new HashSet<>(jdbc.sql("""
                        SELECT id FROM comments WHERE id IN (:ids) AND deleted_at IS NULL AND removed = FALSE AND pending = FALSE""")
                .param("ids", batch.stream().map(Node::id).toList()).query(Long.class).list());
        return batch.stream().filter(n -> live.contains(n.id())).toList();
    }

    /**
     * Keeps one label per comment id that was in the batch and a known stance; ignores unknown ids, duplicates and
     * malformed entries. Unparseable output yields an empty map (those comments simply keep the rules).
     */
    static Map<Long, Stance> validate(String output, List<Node> batch, ObjectMapper mapper) {
        Set<Long> ids = new HashSet<>();
        batch.forEach(n -> ids.add(n.id()));
        Map<Long, Stance> out = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = mapper.readTree(output == null ? "" : output);
        } catch (Exception e) {
            return out;
        }
        if (root == null || !root.path("labels").isArray()) return out;
        for (JsonNode l : root.get("labels")) {
            JsonNode id = l.path("commentId");
            JsonNode st = l.path("stance");
            if (!id.isIntegralNumber() || !st.isTextual() || !ids.contains(id.asLong()) || out.containsKey(id.asLong())) continue;
            try {
                out.put(id.asLong(), Stance.valueOf(st.asText()));
            } catch (IllegalArgumentException ignored) {
                // not one of the six stances: keep the rules for this comment
            }
        }
        return out;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

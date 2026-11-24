package app.howlers.ai;

import app.howlers.ai.AiDtos.Intent;
import app.howlers.ai.AiDtos.Scope;
import app.howlers.ai.AiDtos.Source;
import app.howlers.insight.TextSignals;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Retrieval for Community AI. Permission filtering happens <em>inside</em> the SQL, before anything is ranked,
 * so private-community content the viewer cannot see never reaches the model, and deleted or removed content is
 * never retrieved at all. Every query is index-backed (GIN tsvector) and LIMITed, never a table scan.
 */
@Component
public class Retriever {
    /** Total characters of source text handed to the model per question. */
    static final int CHAR_BUDGET = 18_000;
    static final int MAX_SOURCES = 14;
    static final int MAX_PER_POST = 4;
    /** Hits weaker than this fraction of the best hit are dropped. */
    static final double RELEVANCE_FLOOR = 0.25;

    private final JdbcClient jdbc;

    public Retriever(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Hit(String kind, long postId, Long commentId, String title, String slug, String author, int score,
                       Instant createdAt, String text, double relevance, double controversy) {}

    /** Visibility predicate over aliases p (posts) and cm (communities), parameterised by :viewer. */
    private static final String VISIBLE = """
            p.deleted_at IS NULL AND p.removed = FALSE
            AND (cm.visibility <> 'PRIVATE' OR EXISTS (SELECT 1 FROM community_members vm
                 WHERE vm.community_id = cm.id AND vm.user_id = :viewer))""";

    public List<Source> retrieve(String question, Intent intent, Scope scope, long viewerId) {
        String tsq = QueryAnalyzer.tsQuery(question);
        List<Hit> hits = new ArrayList<>();
        if (scope.postId() != null) {
            // Thread Q&A stays inside the thread: keyword matches only re-rank its own comments.
            hits.addAll(thread(scope.postId(), viewerId));
            if (tsq != null) hits.addAll(comments(tsq, scope, viewerId).stream().filter(h -> h.postId() == scope.postId()).toList());
        } else if (tsq != null) {
            List<Hit> postHits = posts(tsq, scope, viewerId);
            hits.addAll(postHits);
            hits.addAll(comments(tsq, scope, viewerId));
            hits.addAll(repliesOfTopThreads(postHits, viewerId));
        }
        return select(hits, intent, scope);
    }

    /**
     * Replies often answer the question without repeating its keywords ("the GC pauses were the problem" under a
     * "Rust vs Go" post). For the strongest matching threads, bring along their best-received replies, ranked
     * just below the post itself so the relevance floor and budget still apply.
     */
    private List<Hit> repliesOfTopThreads(List<Hit> postHits, long viewerId) {
        List<Hit> out = new ArrayList<>();
        postHits.stream().sorted(Comparator.comparingDouble(Hit::relevance).reversed()).limit(2).forEach(post ->
                thread(post.postId(), viewerId).stream()
                        .filter(h -> h.kind().equals("COMMENT"))
                        .limit(3)
                        .map(h -> new Hit(h.kind(), h.postId(), h.commentId(), h.title(), h.slug(), h.author(), h.score(),
                                h.createdAt(), h.text(), post.relevance() * 0.6, h.controversy()))
                        .forEach(out::add));
        return out;
    }

    /** Thread Q&A: the post itself plus its best comments, regardless of keyword overlap. */
    private List<Hit> thread(long postId, long viewerId) {
        List<Hit> out = new ArrayList<>(jdbc.sql("""
                        SELECT p.id, p.title, cm.slug, u.username, p.score, p.created_at, p.body_text
                        FROM posts p JOIN communities cm ON cm.id = p.community_id LEFT JOIN users u ON u.id = p.author_id
                        WHERE p.id = :pid AND """ + " " + VISIBLE)
                .param("pid", postId).param("viewer", viewerId)
                .query((rs, i) -> new Hit("POST", rs.getLong(1), null, rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), ts(rs.getObject(6, OffsetDateTime.class)), rs.getString(2) + ". " + rs.getString(7), 10, 0))
                .list());
        if (out.isEmpty()) return out;
        out.addAll(jdbc.sql("""
                        SELECT c.id, c.post_id, p.title, cm.slug, u.username, c.score, c.created_at, c.body, c.best_rank, c.controversy
                        FROM comments c JOIN posts p ON p.id = c.post_id JOIN communities cm ON cm.id = p.community_id
                        LEFT JOIN users u ON u.id = c.author_id
                        WHERE c.post_id = :pid AND c.deleted_at IS NULL AND c.removed = FALSE AND """ + " " + VISIBLE + """

                        ORDER BY c.best_rank DESC, c.score DESC LIMIT 30""")
                .param("pid", postId).param("viewer", viewerId)
                .query((rs, i) -> new Hit("COMMENT", rs.getLong(2), rs.getLong(1), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), ts(rs.getObject(7, OffsetDateTime.class)), rs.getString(8),
                        5 + rs.getDouble(9), rs.getDouble(10)))
                .list());
        return out;
    }

    private List<Hit> posts(String tsq, Scope scope, long viewerId) {
        return jdbc.sql("""
                        SELECT p.id, p.title, cm.slug, u.username, p.score, p.created_at, p.body_text,
                               ts_rank_cd(p.search_vector, to_tsquery('english', :q), 32) AS r
                        FROM posts p JOIN communities cm ON cm.id = p.community_id LEFT JOIN users u ON u.id = p.author_id
                        WHERE p.search_vector @@ to_tsquery('english', :q) AND """ + " " + VISIBLE + scopeSql("p") + """

                        ORDER BY r DESC LIMIT 15""")
                .param("q", tsq).param("viewer", viewerId).params(scopeParams(scope))
                .query((rs, i) -> new Hit("POST", rs.getLong(1), null, rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), ts(rs.getObject(6, OffsetDateTime.class)), rs.getString(2) + ". " + rs.getString(7),
                        rs.getDouble(8) * 1.3, 0))
                .list();
    }

    /**
     * Comment vectors are unweighted (class D, which ts_rank_cd scores at 0.1), whereas post bodies are class C (0.4).
     * Ranking comments with uniform 0.4 weights puts both on the same scale, so a strong comment isn't out-ranked
     * by a weakly matching post title.
     */
    private List<Hit> comments(String tsq, Scope scope, long viewerId) {
        return jdbc.sql("""
                        SELECT c.id, c.post_id, p.title, cm.slug, u.username, c.score, c.created_at, c.body,
                               ts_rank_cd('{0.4,0.4,0.4,0.4}', c.search_vector, to_tsquery('english', :q), 32) AS r, c.controversy
                        FROM comments c JOIN posts p ON p.id = c.post_id JOIN communities cm ON cm.id = p.community_id
                        LEFT JOIN users u ON u.id = c.author_id
                        WHERE c.search_vector @@ to_tsquery('english', :q) AND c.deleted_at IS NULL AND c.removed = FALSE
                          AND """ + " " + VISIBLE + scopeSql("c") + """

                        ORDER BY r DESC LIMIT 30""")
                .param("q", tsq).param("viewer", viewerId).params(scopeParams(scope))
                .query((rs, i) -> new Hit("COMMENT", rs.getLong(2), rs.getLong(1), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), ts(rs.getObject(7, OffsetDateTime.class)), rs.getString(8),
                        rs.getDouble(9), rs.getDouble(10)))
                .list();
    }

    private static String scopeSql(String alias) {
        return " AND (CAST(:cid AS bigint) IS NULL OR cm.id = CAST(:cid AS bigint))"
                + " AND (CAST(:since AS integer) IS NULL OR " + alias + ".created_at > now() - make_interval(days => CAST(:since AS integer)))";
    }

    private static Map<String, Object> scopeParams(Scope s) {
        Map<String, Object> m = new HashMap<>();
        m.put("cid", s.communityId());
        m.put("since", s.sinceDays());
        return m;
    }

    /**
     * Final ranking: text relevance × community appreciation (log of score, so popularity informs but can't
     * dominate) with intent-specific boosts; then de-duplicate, cap per thread for diversity, and pack within
     * the character budget.
     */
    private List<Source> select(List<Hit> hits, Intent intent, Scope scope) {
        Map<String, Hit> unique = new HashMap<>();
        for (Hit h : hits) {
            String key = h.kind() + ":" + (h.commentId() != null ? h.commentId() : h.postId());
            Hit prev = unique.get(key);
            if (prev == null || prev.relevance() < h.relevance()) unique.put(key, h);
        }
        Comparator<Hit> byWeight = Comparator.comparingDouble((Hit h) -> weight(h, intent)).reversed();
        List<Hit> ranked = unique.values().stream().sorted(byWeight).toList();
        // Relevance floor: OR-queries match loosely, so drop hits far weaker than the best one (thread Q&A excepted).
        if (scope.postId() == null && !ranked.isEmpty()) {
            double top = weight(ranked.getFirst(), intent);
            ranked = ranked.stream().filter(h -> weight(h, intent) >= top * RELEVANCE_FLOOR).toList();
        }

        List<Source> out = new ArrayList<>();
        Map<Long, Integer> perPost = new HashMap<>();
        Set<String> seenText = new HashSet<>();
        int budget = CHAR_BUDGET;
        int perPostCap = scope.postId() != null ? MAX_SOURCES : MAX_PER_POST;
        for (Hit h : ranked) {
            if (out.size() >= MAX_SOURCES || budget <= 200) break;
            if (perPost.getOrDefault(h.postId(), 0) >= perPostCap) continue;
            String plain = TextSignals.plain(h.text());
            if (plain.length() < (h.kind().equals("POST") ? 5 : 20) || !seenText.add(TextSignals.normalise(plain.length() > 80 ? plain.substring(0, 80) : plain))) continue;
            String excerpt = plain.length() > Math.min(1600, budget) ? plain.substring(0, Math.min(1600, budget) - 1) + "…" : plain;
            budget -= excerpt.length();
            perPost.merge(h.postId(), 1, Integer::sum);
            String link = "/c/" + h.slug() + "/p/" + h.postId() + (h.commentId() != null ? "#c-" + h.commentId() : "");
            out.add(new Source(out.size() + 1, h.kind(), h.postId(), h.commentId(), h.title(), h.slug(), h.author(),
                    h.score(), h.createdAt(), excerpt, link, false));
        }
        return out;
    }

    private static double weight(Hit h, Intent intent) {
        double w = h.relevance() * (1 + Math.log1p(Math.max(h.score(), 0)) * 0.35);
        if (h.score() < 0) w *= 0.6;
        switch (intent) {
            case RESOURCES -> { if (!TextSignals.urls(h.text()).isEmpty()) w *= 1.8; }
            case DEBATE, COMPARE -> w *= 1 + Math.min(h.controversy(), 10) * 0.08;
            case TRENDS -> {
                long days = Math.max(1, (Instant.now().getEpochSecond() - h.createdAt().getEpochSecond()) / 86_400);
                w *= 1 + 2.0 / Math.sqrt(days);
            }
            default -> { }
        }
        return w;
    }

    private static Instant ts(OffsetDateTime t) {
        return t == null ? Instant.EPOCH : t.toInstant();
    }
}

package app.howlers.insight;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.insight.InsightDtos.ArgItem;
import app.howlers.insight.InsightDtos.DigestSections;
import app.howlers.insight.InsightDtos.DigestView;
import app.howlers.insight.InsightDtos.Line;
import app.howlers.insight.ThreadLoader.Snapshot;
import app.howlers.post.PostService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Thread Digest with caching (D-011, D-031). A digest is cached per (post, length, focus) together with the engine
 * that produced it, and marked stale once the thread has grown materially (≥ 5 new comments and ≥ 10%) or a cited
 * comment was edited. Cited comments that have since been deleted, removed or held are filtered out of cached
 * digests before they are served.
 *
 * <p>Engine choice: the LLM engine ({@link LlmDigestEngine#ID}) when a model is configured, otherwise the extractive engine.
 * The model is only called for signed-in viewers, under a per-user hourly limit; when it can't be used (no key, failure,
 * refusal, unverifiable output, limit reached) the extractive engine is used and the response's {@code notice} says
 * why. A fallback is cached for {@link #FALLBACK_RETRY} so a failing provider isn't called on every view.
 *
 * <p>Not @Transactional: the model call can take many seconds and must not pin a database connection.
 */
@Service
public class DigestService {
    public static final Set<String> LENGTHS = Set.of("short", "standard", "detailed");
    public static final Set<String> FOCUSES = Set.of("balanced", "top", "new", "beginner", "technical");
    static final Duration FALLBACK_RETRY = Duration.ofMinutes(10);
    static final int LLM_PER_HOUR = 20;

    private final ThreadLoader loader;
    private final ExtractiveDigestEngine extractive;
    private final LlmDigestEngine llm;
    private final InsightUsage usage;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RateLimiter limiter;
    private final PostService posts;

    public DigestService(ThreadLoader loader, ExtractiveDigestEngine extractive, LlmDigestEngine llm, InsightUsage usage,
                         JdbcClient jdbc, ObjectMapper json, RateLimiter limiter, PostService posts) {
        this.loader = loader;
        this.extractive = extractive;
        this.llm = llm;
        this.usage = usage;
        this.jdbc = jdbc;
        this.json = json;
        this.limiter = limiter;
        this.posts = posts;
    }

    private record Cached(String engine, String payload, int commentCount, Instant generatedAt, String engineNote,
                          String notice, boolean fallback, List<Long> citedIds, String citedVersion) {}

    public DigestView get(long postId, String length, String focus, AuthUser viewer) {
        validate(length, focus);
        posts.context(postId, viewer); // permission check before touching the cache
        Optional<Cached> cached = jdbc.sql("""
                        SELECT engine, payload::text, comment_count, generated_at, engine_note, notice, fallback, cited_ids,
                               cited_version
                        FROM thread_digests WHERE post_id = ? AND length = ? AND focus = ?""")
                .params(postId, length, focus)
                .query((rs, i) -> new Cached(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getString(5), rs.getString(6), rs.getBoolean(7),
                        longs(rs.getArray(8)), rs.getString(9)))
                .optional();
        if (cached.isPresent() && usable(cached.get(), viewer)) return fromCache(postId, length, focus, cached.get());
        return generate(postId, length, focus, viewer, false);
    }

    public DigestView refresh(long postId, String length, String focus, AuthUser viewer) {
        validate(length, focus);
        limiter.check("digest:" + (viewer == null ? "anon" : viewer.id()), 10, Duration.ofHours(1));
        return generate(postId, length, focus, viewer, true);
    }

    private String preferred() {
        return llm.available() ? LlmDigestEngine.ID : ExtractiveDigestEngine.ID;
    }

    private boolean usable(Cached c, AuthUser viewer) {
        if (c.engine().equals(preferred())) return true;
        if (!llm.available()) return false; // e.g. an AI digest cached before the key was removed
        if (viewer == null) return true; // anonymous viewers never trigger model calls; they read what's cached
        return c.fallback() && c.generatedAt().isAfter(Instant.now().minus(FALLBACK_RETRY));
    }

    private DigestView fromCache(long postId, String length, String focus, Cached c) {
        Map<Long, Instant> live = liveComments(postId);
        DigestSections sections = read(c.payload());
        DigestSections visible = retainLive(sections, live.keySet());
        boolean citedChanged = !c.citedIds().stream().allMatch(live::containsKey)
                || (c.citedVersion() != null && !c.citedVersion().equals(version(c.citedIds(), live)));
        String note = c.engineNote() != null ? c.engineNote()
                : c.engine().equals(ExtractiveDigestEngine.ID) ? extractive.note() : "";
        return new DigestView(postId, length, focus, c.engine(), note, c.generatedAt(), c.commentCount(), live.size(),
                isStale(c.commentCount(), live.size()) || citedChanged, visible, c.notice());
    }

    private DigestView generate(long postId, String length, String focus, AuthUser viewer, boolean explicit) {
        Snapshot s = loader.load(postId, viewer);
        if (s.nodes().isEmpty()) throw ApiException.badRequest("There are no comments to summarise yet");

        DigestSections sections = null;
        String engine = ExtractiveDigestEngine.ID;
        String note;
        String notice = null;
        long in = 0, out = 0;
        if (llm.available()) {
            if (viewer == null) {
                notice = "Sign in to get a digest summarised by AI. This is an extractive summary.";
            } else if (!withinLimit(viewer, explicit)) {
                notice = "You've reached the hourly limit for AI digests, so this is an extractive summary.";
            } else {
                try {
                    LlmDigestEngine.Generated g = llm.generate(s.title(), s.postText(), s.nodes(), s.totalLive(), length, focus);
                    sections = g.sections();
                    engine = LlmDigestEngine.ID;
                    in = g.inputTokens();
                    out = g.outputTokens();
                    note = g.note();
                    usage.record("DIGEST", postId, viewer.id(), engine, g.dropped() > 0 ? "repaired" : "ok", in, out);
                    return store(postId, length, focus, engine, note, null, false, sections, s.totalLive(), in, out);
                } catch (LlmDigestEngine.Unavailable e) {
                    usage.record("DIGEST", postId, viewer.id(), LlmDigestEngine.ID, "fallback", e.inputTokens, e.outputTokens);
                    notice = "The AI model couldn't produce this digest (" + e.getMessage() + "), so this is an extractive summary instead.";
                    in = e.inputTokens;
                    out = e.outputTokens;
                }
            }
        }
        sections = extractive.generate(s, length, focus);
        note = llm.available() ? ExtractiveDigestEngine.fallbackNote() : extractive.note();
        boolean fallback = llm.available() && viewer != null;
        return store(postId, length, focus, engine, note, notice, fallback, sections, s.totalLive(), in, out);
    }

    /** Per-user hourly cap on AI digests. On an explicit refresh the 429 is returned; on a view we fall back. */
    private boolean withinLimit(AuthUser viewer, boolean explicit) {
        try {
            limiter.check("digest:llm:" + viewer.id(), LLM_PER_HOUR, Duration.ofHours(1));
            return true;
        } catch (ApiException e) {
            if (explicit || e.status() != HttpStatus.TOO_MANY_REQUESTS) throw e;
            return false;
        }
    }

    private DigestView store(long postId, String length, String focus, String engine, String note, String notice,
                             boolean fallback, DigestSections sections, int count, long in, long out) {
        List<Long> cited = cited(sections);
        Map<Long, Instant> live = liveComments(postId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // match PostgreSQL precision
        jdbc.sql("""
                        INSERT INTO thread_digests (post_id, length, focus, engine, payload, comment_count, generated_at,
                                                    engine_note, notice, fallback, cited_ids, cited_version, input_tokens, output_tokens)
                        VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, CAST(? AS bigint[]), ?, ?, ?)
                        ON CONFLICT (post_id, length, focus) DO UPDATE SET engine = EXCLUDED.engine, payload = EXCLUDED.payload,
                          comment_count = EXCLUDED.comment_count, generated_at = EXCLUDED.generated_at,
                          engine_note = EXCLUDED.engine_note, notice = EXCLUDED.notice, fallback = EXCLUDED.fallback,
                          cited_ids = EXCLUDED.cited_ids, cited_version = EXCLUDED.cited_version,
                          input_tokens = EXCLUDED.input_tokens, output_tokens = EXCLUDED.output_tokens""")
                .params(postId, length, focus, engine, write(sections), count, java.sql.Timestamp.from(now), note, notice,
                        fallback, pgArray(cited), version(cited, live), in == 0 ? null : (int) in, out == 0 ? null : (int) out)
                .update();
        return new DigestView(postId, length, focus, engine, note, now, count, count, false, sections, notice);
    }

    static boolean isStale(int atGeneration, int current) {
        int grown = current - atGeneration;
        return grown >= 5 && grown >= Math.ceil(atGeneration * 0.10);
    }

    /** Every comment a digest points at: its lines plus the comment-backed lists and links. */
    static List<Long> cited(DigestSections s) {
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>(LlmDigestEngine.cited(s));
        for (List<ArgItem> l : Arrays.asList(s.mostUseful(), s.minority(), s.unresolved())) {
            if (l != null) l.forEach(a -> ids.add(a.commentId()));
        }
        if (s.links() != null) s.links().forEach(l -> ids.add(l.commentId()));
        return List.copyOf(ids);
    }

    /** Drops anything citing a comment that is no longer live (deleted, removed or held since generation). */
    static DigestSections retainLive(DigestSections s, Set<Long> live) {
        return new DigestSections(lines(s.summary(), live), s.question(), lines(s.takeaways(), live), lines(s.supporting(), live),
                lines(s.opposing(), live), items(s.mostUseful(), live), items(s.minority(), live), items(s.unresolved(), live),
                s.links() == null ? List.of() : s.links().stream().filter(l -> live.contains(l.commentId())).toList(),
                s.consensus(), lines(s.openQuestions(), live), s.coverage());
    }

    private static List<Line> lines(List<Line> in, Set<Long> live) {
        if (in == null) return List.of();
        List<Line> out = new ArrayList<>();
        for (Line l : in) {
            List<Long> ids = l.sources().stream().filter(live::contains).toList();
            if (ids.isEmpty()) continue;
            if (!live.contains(l.commentId())) {
                if (!l.paraphrase()) continue; // a quote belongs to exactly one comment
                out.add(new Line(l.text(), ids.getFirst(), l.kind(), null, ids, true));
            } else {
                out.add(new Line(l.text(), l.commentId(), l.kind(), l.author(), ids, l.paraphrase()));
            }
        }
        return out;
    }

    private static List<ArgItem> items(List<ArgItem> in, Set<Long> live) {
        return in == null ? List.of() : in.stream().filter(a -> live.contains(a.commentId())).toList();
    }

    private Map<Long, Instant> liveComments(long postId) {
        Map<Long, Instant> out = new HashMap<>();
        jdbc.sql("SELECT id, coalesce(edited_at, created_at) FROM comments WHERE post_id = ? AND deleted_at IS NULL AND removed = FALSE AND pending = FALSE")
                .param(postId)
                .query((rs, i) -> out.put(rs.getLong(1), rs.getObject(2, OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MICROS)))
                .list();
        return out;
    }

    /** Hash of (id, last edit) for the cited comments that are live; changes when any of them is edited. */
    static String version(List<Long> ids, Map<Long, Instant> live) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ids.stream().sorted().filter(live::containsKey)
                    .forEach(id -> md.update((id + ":" + live.get(id).toEpochMilli() + ";").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pgArray(List<Long> ids) {
        return "{" + String.join(",", ids.stream().map(String::valueOf).toList()) + "}";
    }

    private static List<Long> longs(Array a) throws java.sql.SQLException {
        if (a == null) return List.of();
        Object raw = a.getArray();
        if (raw instanceof Long[] l) return Arrays.asList(l);
        if (raw instanceof Object[] o) return Arrays.stream(o).map(x -> ((Number) x).longValue()).toList();
        return List.of();
    }

    private static void validate(String length, String focus) {
        if (!LENGTHS.contains(length)) throw ApiException.field("length", "Unknown digest length");
        if (!FOCUSES.contains(focus)) throw ApiException.field("focus", "Unknown digest focus");
    }

    private String write(DigestSections s) {
        try {
            return json.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private DigestSections read(String payload) {
        try {
            return json.readValue(payload, DigestSections.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

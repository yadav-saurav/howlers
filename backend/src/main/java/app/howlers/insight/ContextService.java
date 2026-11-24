package app.howlers.insight;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.insight.InsightDtos.ArgItem;
import app.howlers.insight.InsightDtos.ContextStats;
import app.howlers.insight.InsightDtos.ContextView;
import app.howlers.insight.InsightDtos.EvidenceLink;
import app.howlers.insight.InsightDtos.MapNode;
import app.howlers.insight.ThreadLoader.Node;
import app.howlers.insight.ThreadLoader.Snapshot;
import app.howlers.post.PostService;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Context Mode: an alternative, structured view of a thread. It never replaces the thread: every item links
 * back to its comment. Both sides are always shown regardless of size (popularity is not truth), and every
 * rules-based label is flagged as suggested so readers can tell people's judgement from heuristics.
 */
@Service
public class ContextService {
    private static final Comparator<Node> STRONGEST = Comparator.comparingDouble(Node::best).reversed()
            .thenComparing(Comparator.comparingInt(Node::score).reversed());

    private final ThreadLoader loader;
    private final JdbcClient jdbc;
    private final PostService posts;
    private final RateLimiter limiter;
    private final StanceSuggester suggester;

    public ContextService(ThreadLoader loader, JdbcClient jdbc, PostService posts, RateLimiter limiter, StanceSuggester suggester) {
        this.suggester = suggester;
        this.loader = loader;
        this.jdbc = jdbc;
        this.posts = posts;
        this.limiter = limiter;
    }

    @Transactional(readOnly = true)
    public ContextView build(long postId, AuthUser viewer) {
        Snapshot s = loader.load(postId, viewer);
        List<Node> nodes = s.nodes();

        Map<Stance, List<ArgItem>> groups = new LinkedHashMap<>();
        Map<Stance, Integer> byStance = new EnumMap<>(Stance.class);
        for (Stance st : Stance.values()) {
            List<Node> in = nodes.stream().filter(n -> n.stance() == st).sorted(STRONGEST).toList();
            byStance.put(st, in.size());
            groups.put(st, in.stream().limit(12).map(ContextService::item).toList());
        }

        List<EvidenceLink> evidence = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Node n : nodes.stream().sorted(STRONGEST).toList()) {
            for (String url : TextSignals.urls(n.body())) {
                if (seen.add(url) && evidence.size() < 20) {
                    evidence.add(new EvidenceLink(url, domain(url), n.id(), TextSignals.excerpt(n.body(), 160)));
                }
            }
        }

        List<ArgItem> openQuestions = nodes.stream()
                .filter(n -> n.stance() == Stance.QUESTION && n.replyCount() == 0)
                .sorted(STRONGEST).limit(8).map(ContextService::item).toList();

        // Agreement: well-received points that drew no pushback (no downvotes, no OPPOSE replies).
        Set<Long> challenged = new HashSet<>();
        nodes.stream().filter(n -> n.stance() == Stance.OPPOSE && n.parentId() != null).forEach(n -> challenged.add(n.parentId()));
        List<ArgItem> agreement = nodes.stream()
                .filter(n -> n.score() >= 3 && n.down() == 0 && !challenged.contains(n.id()))
                .filter(n -> n.stance() != Stance.QUESTION && n.stance() != Stance.OPPOSE)
                .sorted(STRONGEST).limit(5).map(ContextService::item).toList();

        // Disagreement: points that were explicitly challenged, or split the votes.
        List<ArgItem> disagreement = nodes.stream()
                .filter(n -> challenged.contains(n.id()) || n.controversy() > 0)
                .sorted(Comparator.comparingDouble(Node::controversy).reversed().thenComparing(STRONGEST))
                .limit(5).map(ContextService::item).toList();

        List<MapNode> map = nodes.stream().sorted(STRONGEST).limit(80)
                .map(n -> new MapNode(n.id(), n.parentId(), n.stance(), n.suggested(), n.score(), TextSignals.excerpt(n.body(), 70), n.stanceSource()))
                .toList();

        int labelled = (int) nodes.stream().filter(n -> !n.suggested()).count();
        int participants = (int) nodes.stream().map(Node::authorId).filter(Objects::nonNull).distinct().count();
        int ai = (int) nodes.stream().filter(n -> n.stanceSource() == StanceSource.AI).count();
        ContextStats stats = new ContextStats(nodes.size(), s.totalLive(), labelled, nodes.size() - labelled, participants, byStance, ai);
        var post = s.post().post();
        return new ContextView(postId, post.getTitle(), TextSignals.excerpt(s.postText(), 280), stats, groups, evidence,
                openQuestions, agreement, disagreement, map, s.totalLive() > nodes.size(), suggester.request(postId, s, viewer));
    }

    /** One label per user per comment; labelling your own comment counts double in the aggregate. */
    @Transactional
    public void label(long commentId, AuthUser user, Stance stance) {
        limiter.check("stance:" + user.id(), 120, Duration.ofMinutes(10));
        long postId = postOf(commentId);
        posts.context(postId, user);
        jdbc.sql("""
                        INSERT INTO comment_stances (comment_id, user_id, stance) VALUES (?, ?, ?)
                        ON CONFLICT (comment_id, user_id) DO UPDATE SET stance = EXCLUDED.stance, created_at = now()""")
                .params(commentId, user.id(), stance.name()).update();
    }

    @Transactional
    public void unlabel(long commentId, AuthUser user) {
        posts.context(postOf(commentId), user);
        jdbc.sql("DELETE FROM comment_stances WHERE comment_id = ? AND user_id = ?").params(commentId, user.id()).update();
    }

    private long postOf(long commentId) {
        return jdbc.sql("SELECT post_id FROM comments WHERE id = ? AND deleted_at IS NULL AND removed = FALSE AND pending = FALSE")
                .param(commentId).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("Comment"));
    }

    static ArgItem item(Node n) {
        return new ArgItem(n.id(), n.parentId(), TextSignals.excerpt(n.body(), 280), n.author(), n.score(), n.stance(),
                n.suggested(), n.myLabel(), n.labelWeights(), n.stanceSource());
    }

    static String domain(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}

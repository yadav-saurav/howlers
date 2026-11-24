package app.howlers.feed;

import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityService;
import app.howlers.post.PostQueryService;
import app.howlers.post.PostQueryService.Sort;
import app.howlers.post.PostQueryService.Spec;
import app.howlers.post.dto.PostDtos.PostSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feed tabs are thin compositions of a <em>scope</em> (which posts are eligible) and a <em>sort</em>
 * (see docs/RANKING.md). All filtering of private and hidden content happens in PostQueryService.
 */
@Service
public class FeedService {
    private final PostQueryService queries;
    private final CommunityService communities;
    private final CommunityAccess access;
    private final FeedCache cache;

    public FeedService(PostQueryService queries, CommunityService communities, CommunityAccess access, FeedCache cache) {
        this.queries = queries;
        this.communities = communities;
        this.access = access;
        this.cache = cache;
    }

    private static final String JOINED = "p.community_id IN (SELECT community_id FROM community_members WHERE user_id = :viewer)";
    private static final String FOLLOWED = "p.author_id IN (SELECT followee_id FROM user_follows WHERE follower_id = :viewer)";
    private static final String TOPICS = "c.topic_id IN (SELECT topic_id FROM user_topics WHERE user_id = :viewer)";
    /** Popular/All-style tabs: public communities that platform admins haven't quarantined (D-021). */
    private static final String PUBLIC_ONLY = "c.visibility <> 'PRIVATE' AND c.status = 'ACTIVE'";
    /** The viewer's private mutes: muted people leave every feed, muted communities leave the home tabs. */
    private static final String NOT_MUTED_AUTHOR =
            "NOT EXISTS (SELECT 1 FROM user_mutes um WHERE um.muter_id = :viewer AND um.muted_id = p.author_id)";
    private static final String NOT_MUTED_COMMUNITY =
            "NOT EXISTS (SELECT 1 FROM community_mutes cmu WHERE cmu.user_id = :viewer AND cmu.community_id = p.community_id)";
    private static final String NOT_MUTED = NOT_MUTED_AUTHOR + " AND " + NOT_MUTED_COMMUNITY;

    private static String home(String scope) {
        return "(" + scope + ") AND " + NOT_MUTED;
    }

    @Transactional(readOnly = true)
    public CursorPage<PostSummary> home(String tab, String t, String cursor, int limit, AuthUser user) {
        Long viewer = user == null ? null : user.id();
        String normalized = tab == null ? "foryou" : tab.toLowerCase();
        if (viewer == null && (normalized.equals("foryou") || normalized.equals("following"))) normalized = "popular";
        return switch (normalized) {
            case "following" -> queries.page(new Spec(home(JOINED + " OR " + FOLLOWED), Map.of(), Sort.NEW, null, null, cursor, limit, viewer, false));
            case "latest" -> publicPage("latest", new Spec(home(PUBLIC_ONLY), Map.of(), Sort.NEW, null, null, cursor, limit, viewer, false), NOT_MUTED);
            case "rising" -> publicPage("rising", new Spec(home(PUBLIC_ONLY), Map.of(), Sort.RISING, null, null, cursor, limit, viewer, false), NOT_MUTED);
            case "top" -> publicPage("top", new Spec(home(PUBLIC_ONLY), Map.of(), Sort.TOP, window(t), null, cursor, limit, viewer, false), NOT_MUTED);
            case "foryou" -> forYou(cursor, limit, viewer);
            default -> publicPage("popular", new Spec(home(PUBLIC_ONLY), Map.of(), Sort.HOT, null, null, cursor, limit, viewer, false), NOT_MUTED);
        };
    }

    /**
     * First pages of viewer-independent scopes (Popular/All-style tabs, public community feeds) come from the short-lived
     * id cache (D-025): the anonymous id list is cached, then filtered for this viewer (hidden, blocked, muted, and
     * anything deleted or removed since) and hydrated fresh, so per-viewer fields are always computed for the viewer.
     * Later pages and personal tabs are never cached.
     */
    private CursorPage<PostSummary> publicPage(String name, Spec spec, String viewerFilter) {
        if (spec.cursor() != null || !cache.isEnabled()) return queries.page(spec);
        String key = name + ":" + spec.sort() + ":" + (spec.window() == null ? "all" : spec.window().replace(' ', '_')) + ":" + spec.limit();
        Spec anonymous = new Spec(spec.scopeSql(), spec.params(), spec.sort(), spec.window(), spec.tsQuery(), null, spec.limit(), null,
                spec.excludePinned());
        FeedCache.Entry entry = cache.get(key, () -> {
            PostQueryService.IdPage ids = queries.ids(anonymous);
            return new FeedCache.Entry(ids.ids(), ids.nextCursor());
        });
        List<Long> visible = queries.visibleTo(entry.ids(), spec.viewerId(), spec.viewerId() == null ? null : viewerFilter);
        return new CursorPage<>(queries.hydrate(visible, spec.viewerId()), entry.nextCursor());
    }

    /**
     * Personal relevance: joined communities, followed people, and communities in chosen topics, ranked
     * by hot. A brand-new account with no signals gets the popular feed rather than an empty page.
     */
    private CursorPage<PostSummary> forYou(String cursor, int limit, Long viewer) {
        CursorPage<PostSummary> page = queries.page(new Spec(home(JOINED + " OR " + FOLLOWED + " OR (" + TOPICS + " AND " + PUBLIC_ONLY + ")"),
                Map.of(), Sort.HOT, null, null, cursor, limit, viewer, false));
        if (cursor == null && page.items().isEmpty()) {
            return queries.page(new Spec(home(PUBLIC_ONLY), Map.of(), Sort.HOT, null, null, null, limit, viewer, false));
        }
        return page;
    }

    @Transactional(readOnly = true)
    public CursorPage<PostSummary> community(String slug, String sort, String t, String cursor, int limit, AuthUser user) {
        Community c = communities.require(slug);
        access.requireView(c, user);
        Long viewer = user == null ? null : user.id();
        Sort s = switch (sort == null ? "hot" : sort.toLowerCase()) {
            case "new" -> Sort.NEW;
            case "top" -> Sort.TOP;
            case "rising" -> Sort.RISING;
            default -> Sort.HOT;
        };
        boolean pinFirst = s == Sort.HOT;
        Spec spec = new Spec("p.community_id = :cid AND " + NOT_MUTED_AUTHOR, Map.of("cid", c.getId()), s,
                window(t), null, cursor, limit, viewer, pinFirst);
        // Members-only communities are never cached: their list depends on membership.
        CursorPage<PostSummary> page = c.visibility() == Community.Visibility.PRIVATE ? queries.page(spec)
                : publicPage("c" + c.getId(), spec, NOT_MUTED_AUTHOR);
        if (pinFirst && cursor == null) {
            List<PostSummary> pinned = queries.pinned(c.getId(), viewer);
            if (!pinned.isEmpty()) {
                List<PostSummary> merged = new ArrayList<>(pinned);
                merged.addAll(page.items());
                return new CursorPage<>(merged, page.nextCursor());
            }
        }
        return page;
    }

    @Transactional(readOnly = true)
    public CursorPage<PostSummary> byAuthor(long authorId, String cursor, int limit, AuthUser user) {
        return queries.page(new Spec("p.author_id = :aid", Map.of("aid", authorId), Sort.NEW, null, null, cursor, limit,
                user == null ? null : user.id(), false));
    }

    @Transactional(readOnly = true)
    public List<PostSummary> trending(int limit, AuthUser user) {
        return queries.page(new Spec(home(PUBLIC_ONLY) + " AND p.created_at > now() - interval '3 days'", Map.of(), Sort.TOP, null,
                null, null, Math.min(Math.max(limit, 1), 10), user == null ? null : user.id(), false)).items();
    }

    static String window(String t) {
        if (t == null) return "1 day";
        return switch (t.toLowerCase()) {
            case "hour" -> "1 hour";
            case "week" -> "7 days";
            case "month" -> "1 month";
            case "year" -> "1 year";
            case "all" -> null;
            default -> "1 day";
        };
    }
}

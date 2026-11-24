package app.howlers.search;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.community.dto.CommunityDtos.CommunitySummary;
import app.howlers.feed.FeedService;
import app.howlers.post.PostQueryService;
import app.howlers.post.PostQueryService.Sort;
import app.howlers.post.PostQueryService.Spec;
import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL full-text search (weighted tsvector columns + GIN indexes) behind a small service boundary,
 * so a dedicated engine (OpenSearch/Meilisearch) can replace the queries without touching controllers.
 */
@Service
public class SearchService {
    private final JdbcClient jdbc;
    private final PostQueryService posts;
    private final CommunityService communities;
    private final CommunityAccess access;

    public SearchService(JdbcClient jdbc, PostQueryService posts, CommunityService communities, CommunityAccess access) {
        this.jdbc = jdbc;
        this.posts = posts;
        this.communities = communities;
        this.access = access;
    }

    public record PostHit(long id, String title, CommunityRef community) {}

    public record Suggestions(List<CommunityRef> communities, List<UserSummary> users, List<PostHit> posts) {}

    @Transactional(readOnly = true)
    public CursorPage<PostSummary> posts(String q, String community, String sort, String t, String cursor, int limit, AuthUser user) {
        String query = clean(q);
        Map<String, Object> params = new HashMap<>();
        StringBuilder scope = new StringBuilder("p.search_vector @@ websearch_to_tsquery('english', :q)");
        // Quarantined communities are left out of site-wide search, but stay searchable from inside (D-021).
        if (community == null || community.isBlank()) scope.append(" AND c.status = 'ACTIVE'");
        params.put("q", query);
        if (community != null && !community.isBlank()) {
            Community c = communities.require(community);
            access.requireView(c, user);
            scope.append(" AND p.community_id = :cid");
            params.put("cid", c.getId());
        }
        Sort s = switch (sort == null ? "relevance" : sort) {
            case "new" -> Sort.NEW;
            case "top" -> Sort.TOP;
            default -> Sort.RELEVANCE;
        };
        String window = s == Sort.TOP ? (t == null ? null : windowOf(t)) : null;
        return posts.page(new Spec(scope.toString(), params, s, window, query, cursor, limit,
                user == null ? null : user.id(), false));
    }

    @Transactional(readOnly = true)
    public CursorPage<CommunitySummary> communities(String q, String cursor, int limit, AuthUser user) {
        String query = clean(q);
        int offset = Cursors.decodeOffset(cursor);
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM communities
                        WHERE status = 'ACTIVE'
                          AND (search_vector @@ websearch_to_tsquery('english', :q) OR slug ILIKE :prefix OR name ILIKE :contains)
                        ORDER BY (slug ILIKE :prefix) DESC, ts_rank_cd(search_vector, websearch_to_tsquery('english', :q)) DESC,
                                 member_count DESC, id
                        OFFSET :off LIMIT :lim""")
                .param("q", query).param("prefix", like(query) + "%").param("contains", "%" + like(query) + "%")
                .param("off", offset).param("lim", limit + 1).query(Long.class).list();
        boolean more = ids.size() > limit;
        return new CursorPage<>(communities.byIds(more ? ids.subList(0, limit) : ids, user),
                more ? Cursors.encodeOffset(offset + limit) : null);
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummary> users(String q, String cursor, int limit) {
        String query = clean(q);
        int offset = Cursors.decodeOffset(cursor);
        List<UserSummary> rows = jdbc.sql("""
                        SELECT id, username, display_name, avatar_url FROM users
                        WHERE status = 'ACTIVE' AND (username ILIKE :prefix OR display_name ILIKE :contains)
                        ORDER BY (lower(username) = lower(:q)) DESC, (post_karma + comment_karma) DESC, id
                        OFFSET :off LIMIT :lim""")
                .param("q", query).param("prefix", like(query) + "%").param("contains", "%" + like(query) + "%")
                .param("off", offset).param("lim", limit + 1)
                .query((rs, i) -> new UserSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        boolean more = rows.size() > limit;
        return new CursorPage<>(more ? rows.subList(0, limit) : rows, more ? Cursors.encodeOffset(offset + limit) : null);
    }

    @Transactional(readOnly = true)
    public Suggestions suggest(String q, AuthUser user) {
        if (q == null || q.isBlank()) return new Suggestions(List.of(), List.of(), List.of());
        String query = clean(q);
        List<CommunityRef> cs = jdbc.sql("""
                        SELECT id, slug, name, icon_url FROM communities
                        WHERE status = 'ACTIVE' AND (slug ILIKE :prefix OR name ILIKE :contains) ORDER BY member_count DESC LIMIT 5""")
                .param("prefix", like(query) + "%").param("contains", "%" + like(query) + "%")
                .query((rs, i) -> new CommunityRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        List<UserSummary> us = users(query, null, 4).items();
        List<PostHit> ps = posts(query, null, "relevance", null, null, 4, user).items().stream()
                .map(p -> new PostHit(p.id(), p.title(), p.community())).toList();
        return new Suggestions(cs, us, ps);
    }

    private static String clean(String q) {
        if (q == null || q.isBlank()) throw ApiException.field("q", "Enter something to search for");
        String s = q.strip();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Escape LIKE wildcards in user input. */
    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String windowOf(String t) {
        return switch (t) {
            case "day" -> "1 day";
            case "week" -> "7 days";
            case "month" -> "1 month";
            case "year" -> "1 year";
            default -> null;
        };
    }
}

package app.howlers.post;

import app.howlers.common.text.HtmlSanitizer;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.community.dto.CommunityDtos.FlairDto;
import app.howlers.post.dto.PostDtos.MediaRef;
import app.howlers.post.dto.PostDtos.PollDto;
import app.howlers.post.dto.PostDtos.PollOptionDto;
import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.post.dto.PostDtos.SummaryViewer;
import app.howlers.storage.ImageVariants;
import app.howlers.storage.StorageService;
import app.howlers.storage.Thumbnails;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read model for posts. Every list endpoint is two steps: (1) a keyset/offset query that returns only
 * ordered post ids, then (2) {@link #hydrate} which loads everything for that page in a fixed number of
 * batched queries (posts, authors, media, polls, the viewer's votes and saves). No N+1, ever.
 */
@Service
public class PostQueryService {
    private final JdbcClient jdbc;
    private final UserDirectory users;
    private final StorageService storage;

    public PostQueryService(JdbcClient jdbc, UserDirectory users, StorageService storage) {
        this.jdbc = jdbc;
        this.users = users;
        this.storage = storage;
    }

    public enum Sort { HOT, NEW, TOP, RISING, RELEVANCE }

    /**
     * @param scopeSql extra AND-ed predicate over aliases p (posts) and c (communities); may reference
     *                 named params supplied in {@code params}
     * @param window   for TOP: SQL interval literal such as "7 days", or null for all time
     * @param tsQuery  for RELEVANCE: the user's search text (websearch syntax)
     */
    public record Spec(String scopeSql, Map<String, Object> params, Sort sort, String window, String tsQuery,
                       String cursor, int limit, Long viewerId, boolean excludePinned) {}

    /**
     * Hidden posts, private and banned communities are filtered for every list, centrally. Held (pending) posts are
     * stored as removed, so they never reach lists; their author and moderators open them on the post page and in the
     * mod queue. Keeping {@code removed = FALSE} here lets the partial feed indexes apply.
     */
    private static final String VISIBILITY = """
             p.deleted_at IS NULL AND p.removed = FALSE AND c.status <> 'BANNED'
             AND (c.visibility <> 'PRIVATE' OR EXISTS (SELECT 1 FROM community_members vm
                                                       WHERE vm.community_id = c.id AND vm.user_id = :viewer))
             AND NOT EXISTS (SELECT 1 FROM hidden_posts hp WHERE hp.post_id = p.id AND hp.user_id = :viewer)
             AND NOT EXISTS (SELECT 1 FROM user_blocks ub WHERE ub.blocker_id = :viewer AND ub.blocked_id = p.author_id)
            """;

    public CursorPage<PostSummary> page(Spec spec) {
        IdPage ids = ids(spec);
        return new CursorPage<>(hydrate(ids.ids(), spec.viewerId()), ids.nextCursor());
    }

    /** One page of ordered post ids and the cursor after it, without hydration (cacheable for anonymous scopes). */
    public record IdPage(List<Long> ids, String nextCursor) {}

    public IdPage ids(Spec spec) {
        StringBuilder sql = new StringBuilder("SELECT p.id, ");
        Map<String, Object> params = new HashMap<>(spec.params() == null ? Map.of() : spec.params());
        params.put("viewer", spec.viewerId() == null ? -1L : spec.viewerId());
        params.put("limit", spec.limit() + 1);
        Cursors.Key key = Cursors.decode(spec.cursor());
        int offset = 0;

        switch (spec.sort()) {
            case HOT -> sql.append("p.hot_rank AS k ");
            case NEW -> sql.append("p.id AS k ");
            case TOP -> sql.append("p.score AS k ");
            case RISING -> sql.append("""
                    (p.score + 2 * p.comment_count)::float8
                      / power(extract(epoch FROM now() - p.created_at) / 3600.0 + 2, 1.2) AS k """);
            case RELEVANCE -> sql.append("ts_rank_cd(p.search_vector, websearch_to_tsquery('english', :tsq)) AS k ");
        }
        sql.append(" FROM posts p JOIN communities c ON c.id = p.community_id WHERE ").append(VISIBILITY);
        if (spec.scopeSql() != null && !spec.scopeSql().isBlank()) sql.append(" AND (").append(spec.scopeSql()).append(")");
        if (spec.excludePinned()) sql.append(" AND p.pinned = FALSE");
        if (spec.sort() == Sort.TOP && spec.window() != null) {
            sql.append(" AND p.created_at > now() - CAST(:window AS interval)");
            params.put("window", spec.window());
        }
        if (spec.sort() == Sort.RISING) sql.append(" AND p.created_at > now() - interval '24 hours'");
        if (spec.sort() == Sort.RELEVANCE) {
            sql.append(" AND p.search_vector @@ websearch_to_tsquery('english', :tsq)");
            params.put("tsq", spec.tsQuery());
        }

        switch (spec.sort()) {
            case HOT -> {
                if (key != null) {
                    sql.append(" AND (p.hot_rank, p.id) < (:cv, :cid)");
                    params.put("cv", key.value());
                    params.put("cid", key.id());
                }
                sql.append(" ORDER BY p.hot_rank DESC, p.id DESC");
            }
            case NEW -> {
                if (key != null) {
                    sql.append(" AND p.id < :cid");
                    params.put("cid", key.id());
                }
                sql.append(" ORDER BY p.id DESC");
            }
            case TOP -> {
                if (key != null) {
                    sql.append(" AND (p.score, p.id) < (:cv, :cid)");
                    params.put("cv", (int) key.value());
                    params.put("cid", key.id());
                }
                sql.append(" ORDER BY p.score DESC, p.id DESC");
            }
            case RISING, RELEVANCE -> {
                offset = key == null ? 0 : Cursors.decodeOffset(spec.cursor());
                sql.append(" ORDER BY k DESC, p.id DESC OFFSET :offset");
                params.put("offset", offset);
            }
        }
        sql.append(" LIMIT :limit");

        record IdKey(long id, double k) {}
        List<IdKey> rows = jdbc.sql(sql.toString()).params(params)
                .query((rs, i) -> new IdKey(rs.getLong(1), rs.getDouble(2))).list();
        boolean more = rows.size() > spec.limit();
        List<IdKey> pageRows = more ? rows.subList(0, spec.limit()) : rows;
        String next = null;
        if (more) {
            IdKey last = pageRows.getLast();
            next = switch (spec.sort()) {
                case RISING, RELEVANCE -> Cursors.encodeOffset(offset + spec.limit());
                default -> Cursors.encode(last.k(), last.id());
            };
        }
        return new IdPage(pageRows.stream().map(IdKey::id).toList(), next);
    }

    /**
     * The subset of {@code ids}, in the same order, that {@code viewerId} may see in a list right now: the same
     * visibility rules as {@link #page} (deleted, removed, banned, hidden, blocked, private) plus {@code extraSql}
     * (mutes). Used to personalise a cached anonymous id list (D-025).
     */
    public List<Long> visibleTo(List<Long> ids, Long viewerId, String extraSql) {
        if (ids.isEmpty()) return List.of();
        Set<Long> ok = new HashSet<>(jdbc.sql("SELECT p.id FROM posts p JOIN communities c ON c.id = p.community_id WHERE p.id IN (:ids) AND "
                        + VISIBILITY + (extraSql == null || extraSql.isBlank() ? "" : " AND (" + extraSql + ")"))
                .param("ids", ids).param("viewer", viewerId == null ? -1L : viewerId).query(Long.class).list());
        return ids.stream().filter(ok::contains).toList();
    }

    /** Pinned posts for a community, shown above the first page of the HOT sort. */
    public List<PostSummary> pinned(long communityId, Long viewerId) {
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM posts WHERE community_id = ? AND pinned = TRUE AND deleted_at IS NULL
                        AND removed = FALSE AND pending = FALSE ORDER BY id DESC LIMIT 3""")
                .param(communityId).query(Long.class).list();
        return hydrate(ids, viewerId);
    }

    // ------------------------------------------------------------------ hydration

    private record Base(long id, String type, String title, String bodyText, String url, Long authorId,
                        long communityId, String slug, String communityName, String iconUrl, Long flairId,
                        String flairText, String flairColor, List<String> tags, int up, int down, int score,
                        int comments, boolean pinned, boolean locked, boolean removed, Instant createdAt,
                        Instant editedAt, boolean deleted, boolean pending) {}

    public List<PostSummary> hydrate(List<Long> ids, Long viewerId) {
        if (ids.isEmpty()) return List.of();
        Map<Long, Base> base = new HashMap<>();
        jdbc.sql("""
                        SELECT p.id, p.type, p.title, p.body_text, p.url, p.author_id, p.community_id, c.slug, c.name,
                               c.icon_url, p.flair_id, f.text AS flair_text, f.color AS flair_color, p.tags, p.upvotes,
                               p.downvotes, p.score, p.comment_count, p.pinned, p.locked, p.removed, p.created_at,
                               p.edited_at, p.deleted_at IS NOT NULL AS deleted, p.pending
                        FROM posts p JOIN communities c ON c.id = p.community_id
                        LEFT JOIN community_flairs f ON f.id = p.flair_id
                        WHERE p.id IN (:ids)""")
                .param("ids", ids)
                .query((rs, i) -> new Base(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                        rs.getString("body_text"), rs.getString("url"), (Long) rs.getObject("author_id"),
                        rs.getLong("community_id"), rs.getString("slug"), rs.getString("name"), rs.getString("icon_url"),
                        (Long) rs.getObject("flair_id"), rs.getString("flair_text"), rs.getString("flair_color"),
                        textArray(rs.getArray("tags")), rs.getInt("upvotes"), rs.getInt("downvotes"), rs.getInt("score"),
                        rs.getInt("comment_count"), rs.getBoolean("pinned"), rs.getBoolean("locked"),
                        rs.getBoolean("removed"), instant(rs, "created_at"), instant(rs, "edited_at"),
                        rs.getBoolean("deleted"), rs.getBoolean("pending")))
                .list().forEach(b -> base.put(b.id(), b));

        Map<Long, UserSummary> authors = users.summaries(base.values().stream().map(Base::authorId).toList());
        Map<Long, List<MediaRef>> media = media(ids);
        Map<Long, PollDto> polls = polls(ids, viewerId);
        Map<Long, Integer> votes = new HashMap<>();
        Set<Long> saved = new HashSet<>();
        if (viewerId != null) {
            jdbc.sql("SELECT post_id, value FROM post_votes WHERE user_id = :u AND post_id IN (:ids)")
                    .param("u", viewerId).param("ids", ids)
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                    .forEach(e -> votes.put(e.getKey(), e.getValue()));
            saved.addAll(jdbc.sql("SELECT post_id FROM saved_posts WHERE user_id = :u AND post_id IN (:ids)")
                    .param("u", viewerId).param("ids", ids).query(Long.class).list());
        }
        Set<Long> moderated = moderatedCommunities(base.values().stream().map(Base::communityId).distinct().toList(), viewerId);

        List<PostSummary> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Base b = base.get(id);
            if (b == null) continue;
            // Held posts are only ever loaded for their author or a moderator, who see the real content.
            boolean held = b.pending() && !b.deleted();
            boolean hiddenContent = b.deleted() || (b.removed() && !held);
            String title = b.deleted() ? "[deleted]" : b.removed() && !held ? "[removed by moderators]" : b.title();
            out.add(new PostSummary(b.id(), b.type(), title,
                    hiddenContent ? "" : HtmlSanitizer.preview(b.bodyText(), 300),
                    hiddenContent ? null : b.url(), hiddenContent ? null : domain(b.url()),
                    hiddenContent ? List.of() : media.getOrDefault(id, List.of()),
                    hiddenContent ? null : polls.get(id),
                    b.deleted() ? null : authors.get(b.authorId()),
                    new CommunityRef(b.communityId(), b.slug(), b.communityName(), b.iconUrl()),
                    b.flairId() == null ? null : new FlairDto(b.flairId(), b.flairText(), b.flairColor()),
                    b.tags(), b.score(), b.up(), b.down(), b.comments(), votes.getOrDefault(id, 0),
                    saved.contains(id), b.pinned(), b.locked(), b.removed(), b.createdAt(), b.editedAt(), held,
                    new SummaryViewer(moderated.contains(b.communityId()))));
        }
        return out;
    }

    /**
     * Communities on this page the viewer may moderate: one query per page, never one per row. Platform admins
     * moderate everywhere, mirroring {@code CommunityAccess.canModerate}.
     */
    private Set<Long> moderatedCommunities(List<Long> communityIds, Long viewerId) {
        if (viewerId == null || communityIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("""
                        SELECT c.id FROM communities c
                        WHERE c.id IN (:cids)
                          AND (EXISTS (SELECT 1 FROM users u WHERE u.id = :u AND u.role = 'ADMIN')
                               OR EXISTS (SELECT 1 FROM community_members m WHERE m.community_id = c.id AND m.user_id = :u
                                          AND m.role IN ('MODERATOR', 'ADMIN', 'OWNER')))""")
                .param("cids", communityIds).param("u", viewerId).query(Long.class).list());
    }

    private Map<Long, List<MediaRef>> media(Collection<Long> ids) {
        Map<Long, List<MediaRef>> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT pm.post_id, m.id, m.storage_key, m.width, m.height, m.variant_widths FROM post_media pm
                        JOIN media_assets m ON m.id = pm.media_id WHERE pm.post_id IN (:ids)
                        ORDER BY pm.post_id, pm.position""")
                .param("ids", ids)
                .query((rs, i) -> {
                    String key = rs.getString(3);
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new MediaRef(rs.getLong(2),
                            storage.publicUrl(key), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                            Thumbnails.of(rs.getString(6), w -> storage.publicUrl(ImageVariants.keyFor(key, w)))));
                    return null;
                }).list();
        return out;
    }

    public Map<Long, PollDto> polls(Collection<Long> ids, Long viewerId) {
        record PollRow(long postId, boolean multiple, Instant closesAt, int voters) {}
        List<PollRow> polls = jdbc.sql("SELECT post_id, multiple, closes_at, total_voters FROM polls WHERE post_id IN (:ids)")
                .param("ids", ids)
                .query((rs, i) -> new PollRow(rs.getLong(1), rs.getBoolean(2), instant(rs, "closes_at"), rs.getInt(4)))
                .list();
        if (polls.isEmpty()) return Map.of();
        List<Long> pollIds = polls.stream().map(PollRow::postId).toList();
        Map<Long, List<PollOptionDto>> options = new HashMap<>();
        jdbc.sql("SELECT post_id, id, label, votes FROM poll_options WHERE post_id IN (:ids) ORDER BY post_id, position")
                .param("ids", pollIds)
                .query((rs, i) -> {
                    options.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new PollOptionDto(rs.getLong(2), rs.getString(3), rs.getInt(4)));
                    return null;
                }).list();
        Map<Long, List<Long>> mine = new HashMap<>();
        if (viewerId != null) {
            jdbc.sql("SELECT post_id, option_id FROM poll_votes WHERE user_id = :u AND post_id IN (:ids)")
                    .param("u", viewerId).param("ids", pollIds)
                    .query((rs, i) -> {
                        mine.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
                        return null;
                    }).list();
        }
        Instant now = Instant.now();
        Map<Long, PollDto> out = new HashMap<>();
        for (PollRow p : polls) {
            out.put(p.postId(), new PollDto(p.multiple(), p.closesAt(), p.closesAt() != null && p.closesAt().isBefore(now),
                    p.voters(), mine.getOrDefault(p.postId(), List.of()), options.getOrDefault(p.postId(), List.of())));
        }
        return out;
    }

    static String domain(String url) {
        if (url == null) return null;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return null;
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static List<String> textArray(Array a) throws SQLException {
        if (a == null) return List.of();
        return Arrays.stream((Object[]) a.getArray()).map(Objects::toString).toList();
    }
}

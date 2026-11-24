package app.howlers.collection;

import app.howlers.collection.CollectionDtos.AddItem;
import app.howlers.collection.CollectionDtos.CollectionDto;
import app.howlers.collection.CollectionDtos.CommentRef;
import app.howlers.collection.CollectionDtos.ItemDto;
import app.howlers.collection.CollectionDtos.Membership;
import app.howlers.collection.CollectionDtos.SharedCollection;
import app.howlers.collection.CollectionDtos.TagCount;
import app.howlers.collection.CollectionDtos.UpdateCollection;
import app.howlers.collection.CollectionDtos.UpdateItem;
import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.text.Tokens;
import app.howlers.insight.TextSignals;
import app.howlers.post.PostQueryService;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Smart Collections: a personal knowledge library. Collections are private by default; sharing creates an
 * unguessable read-only link. Items are re-checked against the viewer's permissions on every read, so a
 * shared collection never leaks posts from private communities the reader can't see.
 */
@Service
public class CollectionService {
    private static final int MAX_COLLECTIONS = 100;
    private static final int MAX_ITEMS = 2000;

    private final JdbcClient jdbc;
    private final PostService posts;
    private final PostQueryService postQueries;
    private final UserDirectory users;
    private final RateLimiter limiter;

    public CollectionService(JdbcClient jdbc, PostService posts, PostQueryService postQueries, UserDirectory users, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.posts = posts;
        this.postQueries = postQueries;
        this.users = users;
        this.limiter = limiter;
    }

    // ------------------------------------------------------------------ collections

    @Transactional(readOnly = true)
    public List<CollectionDto> mine(AuthUser user) {
        return jdbc.sql("""
                        SELECT c.id, c.name, c.description, c.shared, c.share_token, c.updated_at,
                               (SELECT count(*) FROM collection_items i WHERE i.collection_id = c.id) AS n
                        FROM collections c WHERE c.owner_id = ? ORDER BY c.position, lower(c.name)""")
                .param(user.id())
                .query((rs, i) -> new CollectionDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getString(5), rs.getInt(7), previews(rs.getLong(1)), rs.getObject(6, OffsetDateTime.class).toInstant()))
                .list();
    }

    @Transactional
    public CollectionDto create(AuthUser user, String name, String description) {
        limiter.check("collection:create:" + user.id(), 30, Duration.ofHours(1));
        int count = jdbc.sql("SELECT count(*) FROM collections WHERE owner_id = ?").param(user.id()).query(Integer.class).single();
        if (count >= MAX_COLLECTIONS) throw ApiException.badRequest("You can have up to " + MAX_COLLECTIONS + " collections");
        try {
            long id = jdbc.sql("""
                            INSERT INTO collections (owner_id, name, description, position) VALUES (?, ?, ?, ?) RETURNING id""")
                    .params(user.id(), name.strip(), description == null ? "" : description.strip(), count)
                    .query(Long.class).single();
            return get(user, id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("name", "You already have a collection with that name");
        }
    }

    @Transactional
    public CollectionDto update(AuthUser user, long id, UpdateCollection req) {
        requireOwned(user, id);
        try {
            if (req.name() != null) jdbc.sql("UPDATE collections SET name = ?, updated_at = now() WHERE id = ?").params(req.name().strip(), id).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("name", "You already have a collection with that name");
        }
        if (req.description() != null) jdbc.sql("UPDATE collections SET description = ?, updated_at = now() WHERE id = ?").params(req.description().strip(), id).update();
        if (req.shared() != null) {
            if (req.shared()) {
                jdbc.sql("UPDATE collections SET shared = TRUE, share_token = coalesce(share_token, ?), updated_at = now() WHERE id = ?")
                        .params(Tokens.random(18), id).update();
            } else {
                // Unsharing revokes the link permanently; re-sharing issues a new token.
                jdbc.sql("UPDATE collections SET shared = FALSE, share_token = NULL, updated_at = now() WHERE id = ?").param(id).update();
            }
        }
        return get(user, id);
    }

    @Transactional
    public void delete(AuthUser user, long id) {
        requireOwned(user, id);
        jdbc.sql("DELETE FROM collections WHERE id = ?").param(id).update();
    }

    private CollectionDto get(AuthUser user, long id) {
        return mine(user).stream().filter(c -> c.id() == id).findFirst().orElseThrow(() -> ApiException.notFound("Collection"));
    }

    // ------------------------------------------------------------------ items

    /**
     * Items of one collection (or the whole library when {@code collectionId} is null), filtered by text
     * (title, note, comment body), tag and type. Ordered by manual position within a collection, newest first
     * across the library.
     */
    @Transactional(readOnly = true)
    public List<ItemDto> items(AuthUser user, Long collectionId, String q, String tag, String type) {
        if (collectionId != null) requireOwned(user, collectionId);
        String query = q == null ? "" : q.strip().toLowerCase();
        List<Row> rows = jdbc.sql("""
                        SELECT i.id, i.collection_id, i.item_type, i.post_id, i.comment_id, i.note, i.tags, i.position, i.created_at
                        FROM collection_items i
                        JOIN collections c ON c.id = i.collection_id
                        JOIN posts p ON p.id = i.post_id
                        JOIN communities cm ON cm.id = p.community_id
                        LEFT JOIN comments cmt ON cmt.id = i.comment_id
                        WHERE c.owner_id = :owner
                          AND (CAST(:cid AS bigint) IS NULL OR i.collection_id = CAST(:cid AS bigint))
                          AND (:type = '' OR i.item_type = :type)
                          AND (:tag = '' OR :tag = ANY(i.tags))
                          AND (:q = '' OR lower(p.title) LIKE :like OR lower(i.note) LIKE :like OR lower(coalesce(cmt.body, '')) LIKE :like)
                          AND (cm.visibility <> 'PRIVATE' OR EXISTS (SELECT 1 FROM community_members m
                               WHERE m.community_id = cm.id AND m.user_id = :owner))
                        ORDER BY CASE WHEN CAST(:cid AS bigint) IS NULL THEN 0 ELSE i.position END, i.created_at DESC
                        LIMIT 500""")
                .param("owner", user.id()).param("cid", collectionId).param("type", type == null ? "" : type)
                .param("tag", tag == null ? "" : tag).param("q", query).param("like", "%" + escapeLike(query) + "%")
                .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), (Long) rs.getObject(5),
                        rs.getString(6), tags(rs.getArray(7)), rs.getDouble(8), rs.getObject(9, OffsetDateTime.class).toInstant()))
                .list();
        return hydrate(rows, user.id());
    }

    @Transactional(readOnly = true)
    public List<TagCount> tags(AuthUser user) {
        return jdbc.sql("""
                        SELECT t, count(*) FROM collection_items i JOIN collections c ON c.id = i.collection_id,
                             unnest(i.tags) AS t
                        WHERE c.owner_id = ? GROUP BY t ORDER BY count(*) DESC, t LIMIT 100""")
                .param(user.id()).query((rs, i) -> new TagCount(rs.getString(1), rs.getInt(2))).list();
    }

    /** Where a post (or comment) is already filed, so the UI can show checkmarks. */
    @Transactional(readOnly = true)
    public List<Membership> membership(AuthUser user, long postId, Long commentId) {
        return jdbc.sql("""
                        SELECT i.collection_id, i.id FROM collection_items i JOIN collections c ON c.id = i.collection_id
                        WHERE c.owner_id = ? AND i.post_id = ? AND coalesce(i.comment_id, 0) = ?""")
                .params(user.id(), postId, commentId == null ? 0L : commentId)
                .query((rs, i) -> new Membership(rs.getLong(1), rs.getLong(2))).list();
    }

    @Transactional
    public ItemDto add(AuthUser user, long collectionId, AddItem req) {
        requireOwned(user, collectionId);
        limiter.check("collection:add:" + user.id(), 240, Duration.ofHours(1));
        posts.context(req.postId(), user); // must be able to see what you save
        boolean comment = "COMMENT".equals(req.type());
        if (comment) {
            if (req.commentId() == null) throw ApiException.field("commentId", "Choose a comment");
            Long owner = jdbc.sql("SELECT post_id FROM comments WHERE id = ?").param(req.commentId()).query(Long.class).optional().orElse(null);
            if (!Objects.equals(owner, req.postId())) throw ApiException.field("commentId", "That comment is not on this post");
        }
        int count = jdbc.sql("SELECT count(*) FROM collection_items WHERE collection_id = ?").param(collectionId).query(Integer.class).single();
        if (count >= MAX_ITEMS) throw ApiException.badRequest("This collection is full");
        double pos = jdbc.sql("SELECT coalesce(max(position), 0) + 1 FROM collection_items WHERE collection_id = ?")
                .param(collectionId).query(Double.class).single();
        List<String> tags = req.tags() == null ? List.of() : req.tags().stream().distinct().toList();
        long id;
        try {
            id = jdbc.sql("""
                            INSERT INTO collection_items (collection_id, item_type, post_id, comment_id, note, tags, position)
                            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                    .params(collectionId, req.type(), req.postId(), comment ? req.commentId() : null,
                            req.note() == null ? "" : req.note().strip(), tags.toArray(new String[0]), pos)
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(null, "Already in this collection");
        }
        touch(collectionId);
        return single(user, id);
    }

    @Transactional
    public ItemDto updateItem(AuthUser user, long itemId, UpdateItem req) {
        long collectionId = requireOwnedItem(user, itemId);
        if (req.note() != null) jdbc.sql("UPDATE collection_items SET note = ?, updated_at = now() WHERE id = ?").params(req.note().strip(), itemId).update();
        if (req.tags() != null) {
            jdbc.sql("UPDATE collection_items SET tags = ?, updated_at = now() WHERE id = ?")
                    .params(req.tags().stream().distinct().toArray(String[]::new), itemId).update();
        }
        if (req.collectionId() != null && req.collectionId() != collectionId) {
            requireOwned(user, req.collectionId());
            double pos = jdbc.sql("SELECT coalesce(max(position), 0) + 1 FROM collection_items WHERE collection_id = ?")
                    .param(req.collectionId()).query(Double.class).single();
            try {
                jdbc.sql("UPDATE collection_items SET collection_id = ?, position = ?, updated_at = now() WHERE id = ?")
                        .params(req.collectionId(), pos, itemId).update();
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict(null, "That collection already has this item");
            }
            touch(req.collectionId());
        }
        touch(collectionId);
        return single(user, itemId);
    }

    @Transactional
    public void removeItem(AuthUser user, long itemId) {
        long collectionId = requireOwnedItem(user, itemId);
        jdbc.sql("DELETE FROM collection_items WHERE id = ?").param(itemId).update();
        touch(collectionId);
    }

    /** Persist a manual order. Ids not in the collection are rejected; omitted items keep their relative order after. */
    @Transactional
    public void reorder(AuthUser user, long collectionId, List<Long> itemIds) {
        requireOwned(user, collectionId);
        List<Long> existing = jdbc.sql("SELECT id FROM collection_items WHERE collection_id = ? ORDER BY position")
                .param(collectionId).query(Long.class).list();
        if (!existing.containsAll(itemIds)) throw ApiException.badRequest("Unknown item in order");
        List<Long> order = new ArrayList<>(itemIds);
        existing.stream().filter(id -> !itemIds.contains(id)).forEach(order::add);
        for (int i = 0; i < order.size(); i++) {
            jdbc.sql("UPDATE collection_items SET position = ? WHERE id = ?").params((double) i + 1, order.get(i)).update();
        }
        touch(collectionId);
    }

    // ------------------------------------------------------------------ sharing

    @Transactional(readOnly = true)
    public SharedCollection shared(String token, AuthUser viewer) {
        record C(long id, long ownerId, String name, String description, Instant updated) {}
        C c = jdbc.sql("SELECT id, owner_id, name, description, updated_at FROM collections WHERE share_token = ? AND shared = TRUE")
                .param(token).query((rs, i) -> new C(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> ApiException.notFound("Collection"));
        Long viewerId = viewer == null ? null : viewer.id();
        List<Row> rows = jdbc.sql("""
                        SELECT i.id, i.collection_id, i.item_type, i.post_id, i.comment_id, i.note, i.tags, i.position, i.created_at
                        FROM collection_items i JOIN posts p ON p.id = i.post_id JOIN communities cm ON cm.id = p.community_id
                        WHERE i.collection_id = :cid AND p.deleted_at IS NULL AND p.removed = FALSE
                          AND (cm.visibility <> 'PRIVATE' OR EXISTS (SELECT 1 FROM community_members m
                               WHERE m.community_id = cm.id AND m.user_id = :viewer))
                        ORDER BY i.position LIMIT 500""")
                .param("cid", c.id()).param("viewer", viewerId == null ? -1L : viewerId)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), (Long) rs.getObject(5),
                        rs.getString(6), tags(rs.getArray(7)), rs.getDouble(8), rs.getObject(9, OffsetDateTime.class).toInstant()))
                .list();
        List<ItemDto> items = hydrate(rows, viewerId);
        UserSummary owner = users.summaries(List.of(c.ownerId())).get(c.ownerId());
        CollectionDto dto = new CollectionDto(c.id(), c.name(), c.description(), true, null, items.size(), List.of(), c.updated());
        return new SharedCollection(dto, owner, items);
    }

    // ------------------------------------------------------------------ helpers

    private record Row(long id, long collectionId, String type, long postId, Long commentId, String note, List<String> tags,
                       double position, Instant createdAt) {}

    private ItemDto single(AuthUser user, long itemId) {
        Row r = jdbc.sql("SELECT id, collection_id, item_type, post_id, comment_id, note, tags, position, created_at FROM collection_items WHERE id = ?")
                .param(itemId)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), (Long) rs.getObject(5),
                        rs.getString(6), tags(rs.getArray(7)), rs.getDouble(8), rs.getObject(9, OffsetDateTime.class).toInstant()))
                .single();
        return hydrate(List.of(r), user.id()).getFirst();
    }

    private List<ItemDto> hydrate(List<Row> rows, Long viewerId) {
        if (rows.isEmpty()) return List.of();
        Map<Long, PostSummary> postsById = new HashMap<>();
        postQueries.hydrate(rows.stream().map(Row::postId).distinct().toList(), viewerId).forEach(p -> postsById.put(p.id(), p));
        List<Long> commentIds = rows.stream().map(Row::commentId).filter(Objects::nonNull).toList();
        Map<Long, CommentRef> comments = new HashMap<>();
        if (!commentIds.isEmpty()) {
            record CR(long id, String body, Long authorId, int score, boolean gone) {}
            List<CR> crs = jdbc.sql("SELECT id, body, author_id, score, deleted_at IS NOT NULL OR removed FROM comments WHERE id IN (:ids)")
                    .param("ids", commentIds)
                    .query((rs, i) -> new CR(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), rs.getInt(4), rs.getBoolean(5)))
                    .list();
            Map<Long, UserSummary> authors = users.summaries(crs.stream().map(CR::authorId).toList());
            for (CR c : crs) {
                comments.put(c.id(), new CommentRef(c.id(), c.gone() ? "[deleted]" : TextSignals.excerpt(c.body(), 400),
                        c.gone() ? null : authors.get(c.authorId()), c.score(), c.gone()));
            }
        }
        return rows.stream().filter(r -> postsById.containsKey(r.postId()))
                .map(r -> new ItemDto(r.id(), r.collectionId(), r.type(), postsById.get(r.postId()),
                        r.commentId() == null ? null : comments.get(r.commentId()), r.note(), r.tags(), r.position(), r.createdAt()))
                .toList();
    }

    private List<String> previews(long collectionId) {
        return jdbc.sql("""
                        SELECT m.storage_key FROM collection_items i JOIN post_media pm ON pm.post_id = i.post_id AND pm.position = 0
                        JOIN media_assets m ON m.id = pm.media_id WHERE i.collection_id = ? ORDER BY i.position LIMIT 3""")
                .param(collectionId).query(String.class).list().stream().map(k -> "/api/v1/files/" + k).toList();
    }

    private void requireOwned(AuthUser user, long collectionId) {
        Long owner = jdbc.sql("SELECT owner_id FROM collections WHERE id = ?").param(collectionId).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("Collection"));
        if (owner != user.id()) throw ApiException.notFound("Collection"); // don't reveal others' private collections
    }

    private long requireOwnedItem(AuthUser user, long itemId) {
        record O(long collectionId, long ownerId) {}
        O o = jdbc.sql("SELECT i.collection_id, c.owner_id FROM collection_items i JOIN collections c ON c.id = i.collection_id WHERE i.id = ?")
                .param(itemId).query((rs, i) -> new O(rs.getLong(1), rs.getLong(2))).optional()
                .orElseThrow(() -> ApiException.notFound("Item"));
        if (o.ownerId() != user.id()) throw ApiException.notFound("Item");
        return o.collectionId();
    }

    private void touch(long collectionId) {
        jdbc.sql("UPDATE collections SET updated_at = now() WHERE id = ?").param(collectionId).update();
    }

    private static List<String> tags(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).map(Object::toString).toList();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

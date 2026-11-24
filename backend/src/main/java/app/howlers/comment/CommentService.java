package app.howlers.comment;

import app.howlers.automod.AutomodService;
import app.howlers.comment.dto.CommentDtos.CommentNode;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.comment.dto.CommentDtos.PostRef;
import app.howlers.comment.dto.CommentDtos.UserComment;
import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.text.Mentions;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.CommunityAccess;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.notification.NotificationService;
import app.howlers.post.Post;
import app.howlers.post.PostService;
import app.howlers.post.PostService.PostContext;
import app.howlers.spam.SpamFlags;
import app.howlers.spam.SpamGuard;
import app.howlers.spam.SpamSignals;
import app.howlers.user.UserDirectory;
import app.howlers.user.UserRepository;
import app.howlers.user.dto.UserDtos.UserSummary;
import app.howlers.vote.ScoreMath;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments are an adjacency list (parent_id + depth). A thread is never loaded whole: top-level comments
 * are keyset-paginated, and each page preloads a bounded fan-out of replies per level using a
 * ROW_NUMBER() window, so the cost of a request is O(page × fan-out^levels) regardless of thread size.
 */
@Service
public class CommentService {
    private final app.howlers.common.metrics.HowlersMetrics metrics;
    public static final int MAX_DEPTH = 10;
    private static final String COLS = """
            c.id, c.post_id, c.parent_id, c.depth, c.body, c.author_id, c.score, c.best_rank, c.controversy,
            c.reply_count, c.removed, c.deleted_at IS NOT NULL AS deleted, c.created_at, c.edited_at, c.pending""";
    /** Held comments reach only their author and the post's moderators. */
    private static final String HELD_FILTER = " AND (c.pending = FALSE OR c.author_id = :viewer OR :mod) ";

    private final JdbcClient jdbc;
    private final PostService posts;
    private final CommunityAccess access;
    private final UserDirectory userDirectory;
    private final UserRepository users;
    private final NotificationService notifications;
    private final RateLimiter limiter;
    private final app.howlers.realtime.RealtimePublisher realtime;
    private final app.howlers.realtime.LiveCounters counters;
    private final SpamGuard spam;
    private final SpamFlags spamFlags;
    /** Field-injected to keep the constructor stable for the other modules that build on it. */
    @org.springframework.beans.factory.annotation.Autowired
    private AutomodService automod;

    public CommentService(JdbcClient jdbc, PostService posts, CommunityAccess access, UserDirectory userDirectory,
                          UserRepository users, NotificationService notifications, RateLimiter limiter,
                          app.howlers.realtime.RealtimePublisher realtime, app.howlers.realtime.LiveCounters counters,
                          SpamGuard spam, SpamFlags spamFlags, app.howlers.common.metrics.HowlersMetrics metrics) {
        this.metrics = metrics;
        this.spam = spam;
        this.spamFlags = spamFlags;
        this.realtime = realtime;
        this.counters = counters;
        this.jdbc = jdbc;
        this.posts = posts;
        this.access = access;
        this.userDirectory = userDirectory;
        this.users = users;
        this.notifications = notifications;
        this.limiter = limiter;
    }

    public enum Sort {
        BEST("c.best_rank DESC, c.score DESC, c.id DESC"),
        TOP("c.score DESC, c.id DESC"),
        NEW("c.id DESC"),
        OLD("c.id ASC"),
        CONTROVERSIAL("c.controversy DESC, c.id DESC");

        final String order;

        Sort(String order) {
            this.order = order;
        }

        public static Sort parse(String s) {
            if (s == null) return BEST;
            return switch (s.toLowerCase()) {
                case "top" -> TOP;
                case "new" -> NEW;
                case "old" -> OLD;
                case "controversial" -> CONTROVERSIAL;
                default -> BEST;
            };
        }
    }

    private record Row(long id, long postId, Long parentId, int depth, String body, Long authorId, int score,
                       double best, double controversy, int replyCount, boolean removed, boolean deleted,
                       Instant createdAt, Instant editedAt, boolean pending) {}

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public CursorPage<CommentNode> topLevel(long postId, AuthUser user, String sortParam, String cursor, int limit) {
        PostContext ctx = posts.context(postId, user);
        boolean mod = access.canModerate(ctx.role(), user);
        Sort sort = Sort.parse(sortParam);
        int offset = Cursors.decodeOffset(cursor);
        List<Row> rows = jdbc.sql("SELECT " + COLS + " FROM comments c WHERE c.post_id = :p AND c.parent_id IS NULL" + HELD_FILTER
                        + "ORDER BY " + sort.order + " OFFSET :off LIMIT :lim")
                .param("p", postId).param("off", offset).param("lim", limit + 1)
                .param("viewer", user == null ? -1L : user.id()).param("mod", mod)
                .query(this::row).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        List<CommentNode> nodes = buildTree(page, sort, new int[]{5, 3}, user, mod);
        return new CursorPage<>(nodes, more ? Cursors.encodeOffset(offset + limit) : null);
    }

    @Transactional(readOnly = true)
    public CursorPage<CommentNode> replies(long commentId, AuthUser user, String sortParam, String cursor, int limit) {
        Long postId = jdbc.sql("SELECT post_id FROM comments WHERE id = ?").param(commentId).query(Long.class)
                .optional().orElseThrow(() -> ApiException.notFound("Comment"));
        PostContext ctx = posts.context(postId, user);
        boolean mod = access.canModerate(ctx.role(), user);
        Sort sort = Sort.parse(sortParam);
        int offset = Cursors.decodeOffset(cursor);
        List<Row> rows = jdbc.sql("SELECT " + COLS + " FROM comments c WHERE c.parent_id = :p" + HELD_FILTER
                        + "ORDER BY " + sort.order + " OFFSET :off LIMIT :lim")
                .param("p", commentId).param("off", offset).param("lim", limit + 1)
                .param("viewer", user == null ? -1L : user.id()).param("mod", mod)
                .query(this::row).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        return new CursorPage<>(buildTree(page, sort, new int[]{3}, user, mod), more ? Cursors.encodeOffset(offset + limit) : null);
    }

    /** Offset cursors here: comment ranks change as people vote, and threads are small per page. */
    private List<CommentNode> buildTree(List<Row> roots, Sort sort, int[] fanOut, AuthUser user, boolean mod) {
        Map<Long, List<Row>> children = new HashMap<>();
        List<Row> all = new ArrayList<>(roots);
        List<Long> frontier = roots.stream().filter(r -> r.replyCount() > 0).map(Row::id).toList();
        for (int per : fanOut) {
            if (frontier.isEmpty()) break;
            List<Row> level = jdbc.sql("SELECT * FROM (SELECT " + COLS + ", row_number() OVER (PARTITION BY c.parent_id ORDER BY "
                            + sort.order + ") AS rn FROM comments c WHERE c.parent_id IN (:ids)" + HELD_FILTER
                            + ") x WHERE x.rn <= :per ORDER BY x.rn")
                    .param("ids", frontier).param("per", per)
                    .param("viewer", user == null ? -1L : user.id()).param("mod", mod)
                    .query(this::row).list();
            for (Row r : level) children.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(r);
            all.addAll(level);
            frontier = level.stream().filter(r -> r.replyCount() > 0).map(Row::id).toList();
        }
        Map<Long, UserSummary> authors = userDirectory.summaries(all.stream().map(Row::authorId).toList());
        Map<Long, Integer> myVotes = new HashMap<>();
        if (user != null && !all.isEmpty()) {
            jdbc.sql("SELECT comment_id, value FROM comment_votes WHERE user_id = :u AND comment_id IN (:ids)")
                    .param("u", user.id()).param("ids", all.stream().map(Row::id).toList())
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                    .forEach(e -> myVotes.put(e.getKey(), e.getValue()));
        }
        Set<Long> blocked = blockedBy(user);
        Set<Long> muted = mutedBy(user);
        return roots.stream().map(r -> toNode(r, children, authors, myVotes, blocked, muted, mod)).toList();
    }

    /** Users the viewer has muted; their comments arrive collapsed (still readable on request). Mutes are private. */
    private Set<Long> mutedBy(AuthUser user) {
        if (user == null) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT muted_id FROM user_mutes WHERE muter_id = ?").param(user.id())
                .query(Long.class).list());
    }

    /** Users the viewer has blocked; their comments keep their place in the tree but not their content. */
    private Set<Long> blockedBy(AuthUser user) {
        if (user == null) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT blocked_id FROM user_blocks WHERE blocker_id = ?").param(user.id())
                .query(Long.class).list());
    }

    private CommentNode toNode(Row r, Map<Long, List<Row>> children, Map<Long, UserSummary> authors, Map<Long, Integer> votes,
                               Set<Long> blocked, Set<Long> muted, boolean mod) {
        List<CommentNode> kids = children.getOrDefault(r.id(), List.of()).stream()
                .map(k -> toNode(k, children, authors, votes, blocked, muted, mod)).toList();
        boolean isBlocked = r.authorId() != null && blocked.contains(r.authorId()) && !r.deleted();
        boolean held = r.pending() && !r.deleted(); // only the author and moderators ever receive held rows
        boolean revealRemoved = (mod || held) && r.removed() && !r.deleted();
        boolean hidden = r.deleted() || (r.removed() && !revealRemoved) || isBlocked;
        String body = r.deleted() ? "[deleted]"
                : isBlocked ? "[blocked user]"
                : r.removed() && !revealRemoved ? "[removed by moderators]" : r.body();
        return new CommentNode(r.id(), r.postId(), r.parentId(), r.depth(), body,
                hidden ? null : authors.get(r.authorId()), r.score(), votes.getOrDefault(r.id(), 0), r.deleted(),
                r.removed() && !held, r.createdAt(), r.editedAt(), r.replyCount(), kids, Math.max(0, r.replyCount() - kids.size()),
                isBlocked, held, r.authorId() != null && muted.contains(r.authorId()) && !r.deleted());
    }

    @Transactional(readOnly = true)
    public CursorPage<UserComment> byUser(long authorId, AuthUser viewer, String cursor, int limit) {
        Cursors.Key key = Cursors.decode(cursor);
        record UC(Row row, String title, long communityId, String slug, String name, String icon) {}
        List<UC> rows = jdbc.sql("SELECT " + COLS + """
                        , p.title, cm.id AS cid, cm.slug, cm.name, cm.icon_url
                        FROM comments c JOIN posts p ON p.id = c.post_id JOIN communities cm ON cm.id = p.community_id
                        WHERE c.author_id = :a AND c.deleted_at IS NULL AND c.removed = FALSE AND p.deleted_at IS NULL
                          AND (cm.visibility <> 'PRIVATE' OR EXISTS (SELECT 1 FROM community_members vm
                               WHERE vm.community_id = cm.id AND vm.user_id = :viewer))
                          AND (:hasCursor = FALSE OR c.id < :cid)
                        ORDER BY c.id DESC LIMIT :lim""")
                .param("a", authorId).param("viewer", viewer == null ? -1L : viewer.id())
                .param("hasCursor", key != null).param("cid", key == null ? 0L : key.id()).param("lim", limit + 1)
                .query((rs, i) -> new UC(row(rs, i), rs.getString("title"), rs.getLong("cid"), rs.getString("slug"),
                        rs.getString("name"), rs.getString("icon_url")))
                .list();
        boolean more = rows.size() > limit;
        List<UC> page = more ? rows.subList(0, limit) : rows;
        Map<Long, UserSummary> authors = userDirectory.summaries(List.of(authorId));
        Map<Long, Integer> votes = new HashMap<>();
        if (viewer != null && !page.isEmpty()) {
            jdbc.sql("SELECT comment_id, value FROM comment_votes WHERE user_id = :u AND comment_id IN (:ids)")
                    .param("u", viewer.id()).param("ids", page.stream().map(u -> u.row().id()).toList())
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                    .forEach(e -> votes.put(e.getKey(), e.getValue()));
        }
        List<UserComment> items = page.stream().map(u -> new UserComment(
                toNode(u.row(), Map.of(), authors, votes, Set.of(), Set.of(), false),
                new PostRef(u.row().postId(), u.title(), new CommunityRef(u.communityId(), u.slug(), u.name(), u.icon()))))
                .toList();
        return new CursorPage<>(items, more ? Cursors.encode(0, page.getLast().row().id()) : null);
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public CommentNode create(long postId, AuthUser user, CreateCommentRequest req) {
        limiter.check("comment:" + user.id(), 30, Duration.ofMinutes(10));
        PostContext ctx = posts.context(postId, user);
        Post post = ctx.post();
        if (!post.isLive()) throw ApiException.badRequest("You can't comment on deleted or removed posts");
        if (post.isLocked() && !access.canModerate(ctx.role(), user)) throw ApiException.forbidden("This thread is locked");
        access.requireNotBanned(ctx.community().getId(), user);
        if (!access.canComment(ctx.community(), ctx.role(), user)) throw ApiException.forbidden("Join this community to comment");
        String body = req.body().strip();
        if (body.isEmpty()) throw ApiException.field("body", "Comment can't be empty");

        int depth = 0;
        Long parentAuthor = null;
        if (req.parentId() != null) {
            record Parent(long postId, int depth, Long authorId, boolean live) {}
            Parent parent = jdbc.sql("SELECT post_id, depth, author_id, deleted_at IS NULL AND removed = FALSE FROM comments WHERE id = ?")
                    .param(req.parentId())
                    .query((rs, i) -> new Parent(rs.getLong(1), rs.getInt(2), (Long) rs.getObject(3), rs.getBoolean(4)))
                    .optional().orElseThrow(() -> ApiException.field("parentId", "The comment you're replying to no longer exists"));
            if (parent.postId() != postId) throw ApiException.field("parentId", "Parent comment belongs to another post");
            if (!parent.live()) throw ApiException.badRequest("You can't reply to a deleted comment");
            depth = parent.depth() + 1;
            if (depth > MAX_DEPTH) throw ApiException.badRequest("This thread is too deep to reply further");
            parentAuthor = parent.authorId();
        }

        int linkCount = SpamSignals.linksInText(body);
        SpamGuard.Verdict verdict = spam.checkComment(user, access.canModerate(ctx.role(), user), ctx.community().getId(), body, linkCount);
        // Community automod runs after the global spam checks (D-045); the strongest outcome wins.
        AutomodService.Verdict auto = automod.check(user, access.canModerate(ctx.role(), user), new AutomodService.Input(
                ctx.community().getId(), "COMMENT", user.id(), body, body, linkCount));
        AutomodService.Action outcome = auto.outcome(verdict.held());
        boolean held = outcome == AutomodService.Action.HOLD;
        boolean autoRemoved = outcome == AutomodService.Action.REMOVE;
        long id = jdbc.sql("""
                        INSERT INTO comments (post_id, parent_id, author_id, depth, body, link_count, pending, removed)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(postId, req.parentId(), user.id(), depth, body, Math.min(linkCount, Short.MAX_VALUE), held, held || autoRemoved)
                .query(Long.class).single();
        if (outcome != null) automod.record(auto, outcome, ctx.community(), user.id(), "COMMENT", postId, id, body);
        if (held) {
            // Held: invisible to everyone but the author and moderators; counters and notifications wait for release.
            List<String> rules = new java.util.ArrayList<>(verdict.rules());
            if (auto.first(AutomodService.Action.HOLD) != null) rules.add("AUTOMOD");
            String details = String.join("; ", java.util.stream.Stream.of(verdict.details(), AutomodService.holdDetails(auto))
                    .filter(s -> !s.isBlank()).toList());
            spamFlags.recordHeld(ctx.community().getId(), user.id(), "COMMENT", postId, id, String.join(",", rules),
                    details.length() <= 500 ? details : details.substring(0, 499) + "…",
                    body.length() <= 300 ? body : body.substring(0, 299) + "…");
            realtime.modQueueChanged(ctx.community().getId());
            metrics.commentCreated(true);
            return single(id, user);
        }
        if (autoRemoved) {
            // Removed by automod: kept for the author, the appeal and the mod log; no counters or notifications.
            metrics.commentCreated(true);
            return single(id, user);
        }
        metrics.commentCreated(false);
        publish(postId, id, req.parentId(), parentAuthor, post, user.id(), user.username(), body);
        return single(id, user);
    }

    private void publish(long postId, long id, Long parentId, Long parentAuthor, Post post, long authorId, String authorName, String body) {
        if (parentId != null) {
            jdbc.sql("UPDATE comments SET reply_count = reply_count + 1 WHERE id = ?").param(parentId).update();
        }
        record Counts(int up, int down, int comments, Instant created) {}
        Counts c = jdbc.sql("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ? RETURNING upvotes, downvotes, comment_count, created_at")
                .param(postId).query((rs, i) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant())).single();
        jdbc.sql("UPDATE posts SET hot_rank = ? WHERE id = ?").params(ScoreMath.hot(c.up(), c.down(), c.comments(), c.created()), postId).update();

        if (parentAuthor != null) {
            notifications.commentReply(parentAuthor, authorId, authorName, postId, id, parentId);
        } else if (post.getAuthorId() != null) {
            notifications.postReply(post.getAuthorId(), authorId, authorName, postId, id, post.getTitle());
        }
        for (String name : Mentions.extract(body, 5)) {
            users.findByUsernameIgnoreCase(name).filter(u -> u.isActive())
                    .ifPresent(u -> notifications.mention(u.getId(), authorId, authorName, postId, id));
        }
        realtime.commentCreated(postId, id, parentId, authorId);
        counters.postChanged(postId);
    }

    /** A held comment was approved: counters, notifications and the live insert happen now, as on a normal write. */
    @Transactional
    public void released(long commentId) {
        Row r = load(commentId);
        Post post = posts.requirePost(r.postId());
        Long parentAuthor = r.parentId() == null ? null
                : jdbc.sql("SELECT author_id FROM comments WHERE id = ?").param(r.parentId()).query(Long.class).optional().orElse(null);
        String authorName = r.authorId() == null ? null
                : users.findById(r.authorId()).map(app.howlers.user.User::getUsername).orElse(null);
        publish(r.postId(), commentId, r.parentId(), parentAuthor, post, r.authorId() == null ? 0L : r.authorId(),
                authorName == null ? "[deleted]" : authorName, r.body());
    }

    /** One comment without preloaded replies, under the same visibility rules as the thread (used for live inserts). */
    @Transactional(readOnly = true)
    public CommentNode one(long commentId, AuthUser user) {
        Row r = load(commentId);
        if (r.deleted()) throw ApiException.notFound("Comment");
        PostContext ctx = posts.context(r.postId(), user);
        if (ctx.post().getDeletedAt() != null) throw ApiException.notFound("Comment");
        boolean mod = access.canModerate(ctx.role(), user);
        if (r.pending() && !mod && !(user != null && Objects.equals(r.authorId(), user.id()))) throw ApiException.notFound("Comment");
        return buildTree(List.of(r), Sort.BEST, new int[0], user, mod).getFirst();
    }

    @Transactional
    public CommentNode update(long commentId, AuthUser user, String body) {
        Row r = load(commentId);
        if (r.deleted()) throw ApiException.notFound("Comment");
        if (r.authorId() == null || r.authorId() != user.id()) throw ApiException.forbidden("Only the author can edit this comment");
        PostContext ctx = posts.context(r.postId(), user);
        if (ctx.post().isLocked()) throw ApiException.forbidden("This thread is locked");
        String b = body.strip();
        if (b.isEmpty()) throw ApiException.field("body", "Comment can't be empty");
        jdbc.sql("UPDATE comments SET body = ?, edited_at = now() WHERE id = ?").params(b, commentId).update();
        return single(commentId, user);
    }

    /** Soft delete: the node stays so replies keep their place in the tree. */
    @Transactional
    public void delete(long commentId, AuthUser user) {
        Row r = load(commentId);
        if (r.deleted()) return;
        if (r.authorId() == null || r.authorId() != user.id()) throw ApiException.forbidden("Only the author can delete this comment");
        jdbc.sql("UPDATE comments SET deleted_at = now() WHERE id = ?").param(commentId).update();
    }

    private CommentNode single(long id, AuthUser user) {
        Row r = load(id);
        return buildTree(List.of(r), Sort.BEST, new int[0], user, false).getFirst();
    }

    private Row load(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM comments c WHERE c.id = ?").param(id).query(this::row)
                .optional().orElseThrow(() -> ApiException.notFound("Comment"));
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        OffsetDateTime edited = rs.getObject("edited_at", OffsetDateTime.class);
        return new Row(rs.getLong("id"), rs.getLong("post_id"), (Long) rs.getObject("parent_id"), rs.getInt("depth"),
                rs.getString("body"), (Long) rs.getObject("author_id"), rs.getInt("score"), rs.getDouble("best_rank"),
                rs.getDouble("controversy"), rs.getInt("reply_count"), rs.getBoolean("removed"), rs.getBoolean("deleted"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), edited == null ? null : edited.toInstant(),
                rs.getBoolean("pending"));
    }
}

package app.howlers.post;

import app.howlers.automod.AutomodService;
import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.text.HtmlSanitizer;
import app.howlers.common.text.Mentions;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityFlair;
import app.howlers.community.CommunityRepository;
import app.howlers.community.CommunityRole;
import app.howlers.notification.NotificationService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.post.dto.PostDtos.PollDto;
import app.howlers.post.dto.PostDtos.PostDetail;
import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.post.dto.PostDtos.PostViewer;
import app.howlers.post.dto.PostDtos.UpdatePostRequest;
import app.howlers.spam.SpamFlags;
import app.howlers.spam.SpamGuard;
import app.howlers.spam.SpamSignals;
import app.howlers.storage.MediaAsset;
import app.howlers.storage.MediaAssetRepository;
import app.howlers.user.UserRepository;
import app.howlers.vote.ScoreMath;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostService {
    private final app.howlers.common.metrics.HowlersMetrics metrics;
    private final PostRepository posts;
    private final PostQueryService queries;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final MediaAssetRepository mediaAssets;
    private final HtmlSanitizer sanitizer;
    private final JdbcClient jdbc;
    private final RateLimiter limiter;
    private final NotificationService notifications;
    private final UserRepository users;
    private final app.howlers.realtime.RealtimePublisher realtime;
    private final SpamGuard spam;
    private final SpamFlags spamFlags;
    /** Field-injected to keep the constructor stable for the other modules that build on it. */
    @org.springframework.beans.factory.annotation.Autowired
    private AutomodService automod;

    public PostService(PostRepository posts, PostQueryService queries, CommunityRepository communities,
                       CommunityAccess access, MediaAssetRepository mediaAssets, HtmlSanitizer sanitizer,
                       JdbcClient jdbc, RateLimiter limiter, NotificationService notifications, UserRepository users,
                       app.howlers.realtime.RealtimePublisher realtime, SpamGuard spam, SpamFlags spamFlags,
                       app.howlers.common.metrics.HowlersMetrics metrics) {
        this.metrics = metrics;
        this.realtime = realtime;
        this.spam = spam;
        this.spamFlags = spamFlags;
        this.posts = posts;
        this.queries = queries;
        this.communities = communities;
        this.access = access;
        this.mediaAssets = mediaAssets;
        this.sanitizer = sanitizer;
        this.jdbc = jdbc;
        this.limiter = limiter;
        this.notifications = notifications;
        this.users = users;
    }

    /** Loads a post and the community it lives in, enforcing view permission. */
    public record PostContext(Post post, Community community, Optional<CommunityRole> role) {}

    /** Loads a post with no permission check, for internal callers that already checked (e.g. releasing held content). */
    public Post requirePost(long postId) {
        return posts.findById(postId).orElseThrow(() -> ApiException.notFound("Post"));
    }

    public PostContext context(long postId, AuthUser user) {
        Post p = posts.findById(postId).orElseThrow(() -> ApiException.notFound("Post"));
        Community c = communities.findById(p.getCommunityId()).orElseThrow(() -> ApiException.notFound("Post"));
        Optional<CommunityRole> role = access.roleOf(c, user);
        access.requireView(c, user, role);
        // Held content: only its author and the community's moderators know it exists.
        if (p.isPending() && !(user != null && Objects.equals(p.getAuthorId(), user.id())) && !access.canModerate(role, user)) {
            throw ApiException.notFound("Post");
        }
        return new PostContext(p, c, role);
    }

    @Transactional
    public long create(AuthUser user, CreatePostRequest req) {
        limiter.check("post:create:" + user.id(), 10, Duration.ofHours(1));
        Community c = communities.findBySlug(req.communitySlug())
                .orElseThrow(() -> ApiException.field("communitySlug", "Community not found"));
        Optional<CommunityRole> role = access.roleOf(c, user);
        access.requireNotBanned(c.getId(), user);
        if (!access.canPost(c, role, user)) {
            throw ApiException.forbidden(c.visibility() == Community.Visibility.RESTRICTED
                    ? "Only approved contributors can post here" : "Join this community to post");
        }
        Post.Type type = Post.Type.valueOf(req.type());
        String title = req.title().strip();
        if (title.isEmpty()) throw ApiException.field("title", "Title is required");

        Integer dup = jdbc.sql("""
                        SELECT count(*) FROM posts WHERE author_id = ? AND community_id = ? AND lower(title) = lower(?)
                        AND created_at > now() - interval '24 hours' AND deleted_at IS NULL""")
                .params(user.id(), c.getId(), title).query(Integer.class).single();
        if (dup > 0) throw ApiException.conflict("title", "You already posted this here in the last day");

        String html = sanitizer.sanitize(req.bodyHtml());
        if (html.length() > 40_000) throw ApiException.field("bodyHtml", "Post body is too long");
        String text = sanitizer.toText(html);

        String url = null;
        if (type == Post.Type.LINK) url = validateUrl(req.url());

        List<MediaAsset> media = List.of();
        if (type == Post.Type.IMAGE) {
            if (req.mediaIds() == null || req.mediaIds().isEmpty()) throw ApiException.field("mediaIds", "Add at least one image");
            List<Long> ids = List.copyOf(new LinkedHashSet<>(req.mediaIds()));
            media = mediaAssets.findByIdInAndOwnerId(ids, user.id());
            if (media.size() != ids.size()) throw ApiException.field("mediaIds", "Unknown image upload");
            if (media.stream().anyMatch(MediaAsset::isPrivate)) {
                throw ApiException.field("mediaIds", "Images sent in direct messages can't be used in posts");
            }
            Map<Long, MediaAsset> byId = new java.util.HashMap<>();
            media.forEach(m -> byId.put(m.getId(), m));
            media = ids.stream().map(byId::get).toList();
        }
        if (type == Post.Type.POLL && req.poll() == null) throw ApiException.field("poll", "Add poll options");

        Long flairId = validateFlair(c, req.flairId());
        List<String> tags = req.tags() == null ? List.of() : req.tags().stream().map(String::toLowerCase).distinct().toList();

        String contentHash = SpamSignals.contentHash(title, text);
        String linkHash = SpamSignals.linkHash(url);
        int linkCount = SpamSignals.linksInPost(html, url);
        SpamGuard.Verdict verdict = spam.checkPost(user, access.canModerate(role, user),
                new SpamGuard.PostInput(c.getId(), title, text, contentHash, linkHash, linkCount));
        // Community automod runs after the global spam checks (D-045); the strongest outcome wins.
        AutomodService.Verdict auto = automod.check(user, access.canModerate(role, user), new AutomodService.Input(
                c.getId(), "POST", user.id(), title + "\n" + text, html + " " + (url == null ? "" : url), linkCount));
        AutomodService.Action outcome = auto.outcome(verdict.held());
        boolean held = outcome == AutomodService.Action.HOLD;
        boolean autoRemoved = outcome == AutomodService.Action.REMOVE;

        Post post = posts.saveAndFlush(new Post(c.getId(), user.id(), type, title, html, text, url, flairId, tags,
                ScoreMath.hot(0, 0, 0, Instant.now())));
        jdbc.sql("UPDATE posts SET content_hash = ?, link_hash = ?, link_count = ?, pending = ?, removed = ? WHERE id = ?")
                .params(contentHash, linkHash, Math.min(linkCount, Short.MAX_VALUE), held, held || autoRemoved, post.getId())
                .update();

        for (int i = 0; i < media.size(); i++) {
            jdbc.sql("INSERT INTO post_media (post_id, media_id, position) VALUES (?, ?, ?)")
                    .params(post.getId(), media.get(i).getId(), i).update();
        }
        if (type == Post.Type.POLL) createPoll(post.getId(), req);
        if (outcome != null) automod.record(auto, outcome, c, user.id(), "POST", post.getId(), null, title);
        if (held) {
            // Held: no counters, mentions or live announcement until a moderator releases it (see released()).
            List<String> rules = new java.util.ArrayList<>(verdict.rules());
            if (auto.first(AutomodService.Action.HOLD) != null) rules.add("AUTOMOD");
            String details = String.join("; ", java.util.stream.Stream.of(verdict.details(), AutomodService.holdDetails(auto))
                    .filter(s -> !s.isBlank()).toList());
            spamFlags.recordHeld(c.getId(), user.id(), "POST", post.getId(), null, String.join(",", rules),
                    clip(details, 500), clip(title, 300));
            realtime.modQueueChanged(c.getId());
            metrics.postCreated(type.name(), true);
            return post.getId();
        }
        if (autoRemoved) {
            // Removed by automod: stored for the author, the appeal and the mod log, but never published.
            metrics.postCreated(type.name(), true);
            return post.getId();
        }
        metrics.postCreated(type.name(), false);
        publish(post.getId(), c, user.id(), user.username(), text);
        return post.getId();
    }

    private void publish(long postId, Community c, long authorId, String authorName, String text) {
        jdbc.sql("UPDATE communities SET post_count = post_count + 1 WHERE id = ?").param(c.getId()).update();
        notifyMentions(authorId, authorName, text, postId);
        realtime.postCreated(postId, c.getId(), c.getSlug(), c.visibility() == Community.Visibility.PRIVATE, authorId);
    }

    /** A held post was approved: it now counts and notifies exactly as if it had been published normally. */
    @Transactional
    public void released(long postId) {
        Post p = posts.findById(postId).orElseThrow(() -> ApiException.notFound("Post"));
        Community c = communities.findById(p.getCommunityId()).orElseThrow(() -> ApiException.notFound("Community"));
        String authorName = p.getAuthorId() == null ? null
                : users.findById(p.getAuthorId()).map(app.howlers.user.User::getUsername).orElse(null);
        if (p.getAuthorId() == null || authorName == null) {
            jdbc.sql("UPDATE communities SET post_count = post_count + 1 WHERE id = ?").param(c.getId()).update();
            return;
        }
        publish(postId, c, p.getAuthorId(), authorName, p.getBodyText());
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private void createPoll(long postId, CreatePostRequest req) {
        var poll = req.poll();
        List<String> labels = poll.options().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (labels.size() < 2) throw ApiException.field("poll", "A poll needs at least two options");
        if (new HashSet<>(labels.stream().map(String::toLowerCase).toList()).size() != labels.size()) {
            throw ApiException.field("poll", "Poll options must be different");
        }
        if (poll.closesAt() != null && poll.closesAt().isBefore(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.field("poll", "Closing time must be in the future");
        }
        jdbc.sql("INSERT INTO polls (post_id, multiple, closes_at) VALUES (?, ?, ?)")
                .params(postId, poll.multiple(), poll.closesAt() == null ? null : java.sql.Timestamp.from(poll.closesAt()))
                .update();
        for (int i = 0; i < labels.size(); i++) {
            jdbc.sql("INSERT INTO poll_options (post_id, position, label) VALUES (?, ?, ?)").params(postId, i, labels.get(i)).update();
        }
    }

    @Transactional(readOnly = true)
    public PostDetail detail(long postId, AuthUser user) {
        PostContext ctx = context(postId, user);
        Post p = ctx.post();
        Long viewerId = user == null ? null : user.id();
        PostSummary s = queries.hydrate(List.of(postId), viewerId).getFirst();
        boolean owner = user != null && p.getAuthorId() != null && p.getAuthorId() == user.id();
        boolean mod = access.canModerate(ctx.role(), user);
        boolean live = p.getDeletedAt() == null;
        boolean showBody = live && (!p.isRemoved() || mod || owner);
        return PostDetail.of(s, showBody ? p.getBodyHtml() : "", new PostViewer(owner && live, (owner || mod) && live, mod));
    }

    @Transactional
    public void update(long postId, AuthUser user, UpdatePostRequest req) {
        PostContext ctx = context(postId, user);
        Post p = ctx.post();
        if (p.getDeletedAt() != null) throw ApiException.notFound("Post");
        if (p.getAuthorId() == null || p.getAuthorId() != user.id()) throw ApiException.forbidden("Only the author can edit this post");
        if (p.isLocked()) throw ApiException.forbidden("This post is locked");
        if (req.title() != null) p.setTitle(req.title().strip());
        if (req.bodyHtml() != null) {
            String html = sanitizer.sanitize(req.bodyHtml());
            if (html.length() > 40_000) throw ApiException.field("bodyHtml", "Post body is too long");
            p.setBody(html, sanitizer.toText(html));
        }
        if (req.tags() != null) p.setTags(req.tags().stream().map(String::toLowerCase).distinct().toList());
        if (req.flairId() != null) p.setFlairId(validateFlair(ctx.community(), req.flairId() == 0 ? null : req.flairId()));
        p.markEdited();
    }

    @Transactional
    public void delete(long postId, AuthUser user) {
        PostContext ctx = context(postId, user);
        Post p = ctx.post();
        if (p.getDeletedAt() != null) return;
        boolean owner = p.getAuthorId() != null && p.getAuthorId() == user.id();
        if (!owner) throw ApiException.forbidden("Only the author can delete this post");
        p.softDelete();
        jdbc.sql("UPDATE communities SET post_count = GREATEST(post_count - 1, 0) WHERE id = ?").param(p.getCommunityId()).update();
    }

    @Transactional
    public void setSaved(long postId, AuthUser user, boolean saved) {
        context(postId, user);
        if (saved) {
            jdbc.sql("INSERT INTO saved_posts (user_id, post_id) VALUES (?, ?) ON CONFLICT DO NOTHING").params(user.id(), postId).update();
        } else {
            jdbc.sql("DELETE FROM saved_posts WHERE user_id = ? AND post_id = ?").params(user.id(), postId).update();
        }
    }

    @Transactional
    public void setHidden(long postId, AuthUser user, boolean hidden) {
        context(postId, user);
        if (hidden) {
            jdbc.sql("INSERT INTO hidden_posts (user_id, post_id) VALUES (?, ?) ON CONFLICT DO NOTHING").params(user.id(), postId).update();
        } else {
            jdbc.sql("DELETE FROM hidden_posts WHERE user_id = ? AND post_id = ?").params(user.id(), postId).update();
        }
    }

    /** Saved posts, newest save first; keyset on (saved_at micros, post id). */
    @Transactional(readOnly = true)
    public CursorPage<PostSummary> saved(AuthUser user, String cursor, int limit) {
        Cursors.Key key = Cursors.decode(cursor);
        record Row(long postId, long micros) {}
        List<Row> rows = jdbc.sql("""
                        SELECT s.post_id, (extract(epoch FROM s.created_at) * 1000000)::bigint AS us
                        FROM saved_posts s JOIN posts p ON p.id = s.post_id
                        WHERE s.user_id = :u AND p.deleted_at IS NULL
                          AND (:hasCursor = FALSE OR (s.created_at, s.post_id) < (timestamptz 'epoch' + :ts * interval '1 microsecond', :cid))
                        ORDER BY s.created_at DESC, s.post_id DESC LIMIT :limit""")
                .param("u", user.id()).param("hasCursor", key != null)
                .param("ts", key == null ? 0L : (long) key.value()).param("cid", key == null ? 0L : key.id())
                .param("limit", limit + 1)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2))).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        String next = more ? Cursors.encode(page.getLast().micros(), page.getLast().postId()) : null;
        return new CursorPage<>(queries.hydrate(page.stream().map(Row::postId).toList(), user.id()), next);
    }

    /**
     * Poll voting replaces the viewer's previous choice atomically. Counters are adjusted with relative
     * updates under a per-(poll,user) advisory lock so concurrent submissions cannot double count.
     */
    @Transactional
    public PollDto votePoll(long postId, AuthUser user, List<Long> optionIds) {
        PostContext ctx = context(postId, user);
        if (!ctx.post().isLive()) throw ApiException.notFound("Post");
        PollDto poll = queries.polls(List.of(postId), user.id()).get(postId);
        if (poll == null) throw ApiException.badRequest("This post has no poll");
        if (poll.closed()) throw ApiException.badRequest("This poll has closed");
        Set<Long> chosen = new LinkedHashSet<>(optionIds);
        if (!poll.multiple() && chosen.size() > 1) throw ApiException.badRequest("Choose one option");
        Set<Long> valid = new HashSet<>(poll.options().stream().map(o -> o.id()).toList());
        if (!valid.containsAll(chosen)) throw ApiException.badRequest("Unknown poll option");

        jdbc.sql("SELECT pg_advisory_xact_lock(?, ?)").params(Long.hashCode(postId) ^ 0x504F4C4C, Long.hashCode(user.id()))
                .query(Object.class).list();
        List<Long> previous = jdbc.sql("SELECT option_id FROM poll_votes WHERE post_id = ? AND user_id = ?")
                .params(postId, user.id()).query(Long.class).list();
        for (Long o : previous) {
            jdbc.sql("UPDATE poll_options SET votes = GREATEST(votes - 1, 0) WHERE id = ?").param(o).update();
        }
        jdbc.sql("DELETE FROM poll_votes WHERE post_id = ? AND user_id = ?").params(postId, user.id()).update();
        for (Long o : chosen) {
            jdbc.sql("INSERT INTO poll_votes (option_id, user_id, post_id) VALUES (?, ?, ?)").params(o, user.id(), postId).update();
            jdbc.sql("UPDATE poll_options SET votes = votes + 1 WHERE id = ?").param(o).update();
        }
        if (previous.isEmpty() && !chosen.isEmpty()) {
            jdbc.sql("UPDATE polls SET total_voters = total_voters + 1 WHERE post_id = ?").param(postId).update();
        }
        return queries.polls(List.of(postId), user.id()).get(postId);
    }

    private Long validateFlair(Community c, Long flairId) {
        if (flairId == null) return null;
        return c.getFlairs().stream().map(CommunityFlair::getId).filter(flairId::equals).findFirst()
                .orElseThrow(() -> ApiException.field("flairId", "That flair does not belong to this community"));
    }

    private static String validateUrl(String raw) {
        if (raw == null || raw.isBlank()) throw ApiException.field("url", "Add a link");
        try {
            URI u = URI.create(raw.strip());
            if (!"http".equalsIgnoreCase(u.getScheme()) && !"https".equalsIgnoreCase(u.getScheme())) {
                throw ApiException.field("url", "Links must start with http:// or https://");
            }
            if (u.getHost() == null || !u.getHost().contains(".")) throw ApiException.field("url", "That doesn't look like a valid link");
            return u.toString();
        } catch (IllegalArgumentException e) {
            throw ApiException.field("url", "That doesn't look like a valid link");
        }
    }

    private void notifyMentions(long actorId, String actorName, String text, long postId) {
        for (String name : Mentions.extract(text, 5)) {
            users.findByUsernameIgnoreCase(name).filter(u -> u.isActive())
                    .ifPresent(u -> notifications.mention(u.getId(), actorId, actorName, postId, null));
        }
    }
}

package app.howlers.automod;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityRepository;
import app.howlers.notification.NotificationService;
import app.howlers.realtime.RealtimePublisher;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import com.google.re2j.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-community automod (D-045). Moderators write rules (keywords, an RE2 regex, a link limit, blocked domains, a
 * minimum account age or karma); each rule holds, removes or flags matching posts and comments at write time, after
 * the global spam checks. The strongest outcome wins (remove, then hold, then flag; a spam hold counts as hold).
 * Moderators of the community and platform admins are exempt. Automod never bans anyone. Every hit is stored in
 * {@code automod_hits} and the community's {@code moderation_actions} log, and the author sees the rule's reason.
 */
@Service
public class AutomodService {
    private static final Logger log = LoggerFactory.getLogger(AutomodService.class);
    public static final int MAX_RULES = 50;
    static final int PREVIEW_POSTS = 200;
    static final int PREVIEW_COMMENTS = 300;
    static final int PREVIEW_MATCHES = 50;

    private final JdbcClient jdbc;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final UserDirectory directory;
    private final NotificationService notifications;
    private final RealtimePublisher realtime;
    private final RateLimiter limiter;

    public AutomodService(JdbcClient jdbc, CommunityRepository communities, CommunityAccess access, UserDirectory directory,
                          NotificationService notifications, RealtimePublisher realtime, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.communities = communities;
        this.access = access;
        this.directory = directory;
        this.notifications = notifications;
        this.realtime = realtime;
        this.limiter = limiter;
    }

    public enum Kind { KEYWORD, REGEX, LINK_LIMIT, ACCOUNT_AGE, KARMA, DOMAIN }

    public enum Action { FLAG, HOLD, REMOVE } // ordinal = strength

    public record RuleRequest(String name, String kind, String pattern, Integer threshold, String appliesTo, String action,
                              String reason, Boolean enabled) {}

    public record RuleDto(long id, String name, String kind, String pattern, Integer threshold, String appliesTo,
                          String action, String reason, boolean enabled, UserSummary createdBy, Instant createdAt,
                          Instant updatedAt, long hits30d) {}

    public record HitDto(long id, Long ruleId, String ruleName, String action, String outcome, String targetType,
                         Long postId, Long commentId, UserSummary author, String detail, String excerpt, Instant createdAt) {}

    public record PreviewMatch(String targetType, long postId, Long commentId, UserSummary author, String excerpt,
                               String detail, boolean authorExempt, Instant createdAt) {}

    public record PreviewResult(int scannedPosts, int scannedComments, int matchCount, List<PreviewMatch> matches,
                                int timedOut) {}

    /** What is being written. {@code text} is plain text (title and body); {@code raw} also holds HTML and the link URL. */
    public record Input(long communityId, String targetType, long authorId, String text, String raw, int linkCount) {}

    public record Hit(Long ruleId, String ruleName, Action action, String reason, String detail) {}

    public record Verdict(List<Hit> hits) {
        public static final Verdict NONE = new Verdict(List.of());

        public Action strongest() {
            return hits.stream().map(Hit::action).max(Enum::compareTo).orElse(null);
        }

        /** The final outcome once a global spam hold (if any) is folded in, or null when nothing applies. */
        public Action outcome(boolean spamHeld) {
            Action a = strongest();
            if (spamHeld && (a == null || a.compareTo(Action.HOLD) < 0)) return Action.HOLD;
            return a;
        }

        public Hit first(Action action) {
            return hits.stream().filter(h -> h.action() == action).findFirst().orElse(null);
        }
    }

    /** A compiled rule. */
    private record Rule(long id, String name, Kind kind, String pattern, Integer threshold, String appliesTo, Action action,
                        String reason, Pattern compiled, Set<String> domains) {
        boolean appliesTo(String targetType) {
            return "BOTH".equals(appliesTo) || ("POST".equals(targetType) ? "POSTS" : "COMMENTS").equals(appliesTo);
        }
    }

    // ------------------------------------------------------------------ write-time check

    /** Evaluates every enabled rule of the community. Never throws for a broken stored rule; that rule is skipped. */
    public Verdict check(AuthUser author, boolean exempt, Input in) {
        if (exempt || author.isAdmin()) return Verdict.NONE;
        List<Rule> rules = loadRules(in.communityId(), true);
        if (rules.isEmpty()) return Verdict.NONE;
        Author a = author(in.authorId());
        List<Hit> hits = new ArrayList<>();
        for (Rule r : rules) {
            if (!r.appliesTo(in.targetType())) continue;
            String detail = evaluate(r, in.text(), in.raw(), in.linkCount(), a);
            if (detail != null) hits.add(new Hit(r.id(), r.name(), r.action(), r.reason(), detail));
        }
        return new Verdict(hits);
    }

    /**
     * Records the hits for content that has been stored (ids known) and tells the author when it was held or removed.
     * Called inside the write transaction, so a rolled-back write leaves no trace.
     */
    public void record(Verdict v, Action outcome, Community c, long authorId, String targetType, long postId, Long commentId,
                       String excerpt) {
        if (v.hits().isEmpty()) return;
        boolean comment = "COMMENT".equals(targetType);
        String what = comment ? "comment" : "post";
        String clipped = AutomodMatcher.clip(excerpt == null ? "" : excerpt.strip().replaceAll("\\s+", " "), 300);
        for (Hit h : v.hits()) {
            jdbc.sql("""
                            INSERT INTO automod_hits (community_id, rule_id, rule_name, action, outcome, target_type, post_id,
                                                      comment_id, author_id, detail, excerpt)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(c.getId(), h.ruleId(), h.ruleName(), h.action().name(), outcome.name(), targetType, postId,
                            commentId, authorId, AutomodMatcher.clip(h.detail(), 300), clipped)
                    .update();
            if (h.action() == Action.FLAG) {
                logAction(c.getId(), "AUTOMOD_FLAG", authorId, postId, commentId, "Automod: " + h.ruleName() + " (" + h.detail() + ")");
            }
        }
        if (outcome == Action.REMOVE) {
            Hit h = v.first(Action.REMOVE);
            long actionId = logAction(c.getId(), comment ? "REMOVE_COMMENT" : "REMOVE_POST", authorId, postId, commentId,
                    "Automod: " + h.ruleName() + ": " + h.reason());
            notifications.contentRemoved(authorId, c.getSlug(), what, actionId, h.reason());
        } else if (outcome == Action.HOLD) {
            Hit h = v.first(Action.HOLD);
            if (h != null) {
                logAction(c.getId(), "AUTOMOD_HOLD", authorId, postId, commentId, "Automod: " + h.ruleName() + ": " + h.reason());
                notifications.automodHeld(authorId, c.getSlug(), what, postId, commentId, h.reason());
            }
        }
        realtime.modQueueChanged(c.getId());
    }

    /** Spam-flag fields for content that automod (alone or with the spam checks) holds. */
    public static String holdDetails(Verdict v) {
        Hit h = v.first(Action.HOLD);
        return h == null ? "" : "automod rule “" + h.ruleName() + "”: " + h.detail();
    }

    // ------------------------------------------------------------------ rules CRUD (moderators)

    @Transactional(readOnly = true)
    public List<RuleDto> rules(String slug, AuthUser user) {
        Community c = moderated(slug, user);
        return ruleDtos(c.getId(), 0);
    }

    @Transactional
    public RuleDto create(String slug, AuthUser user, RuleRequest req) {
        Community c = moderated(slug, user);
        limiter.check("automod:write:" + user.id(), 60, Duration.ofHours(1));
        long n = jdbc.sql("SELECT count(*) FROM automod_rules WHERE community_id = ?").param(c.getId()).query(Long.class).single();
        if (n >= MAX_RULES) throw ApiException.badRequest("A community can have up to " + MAX_RULES + " automod rules");
        Valid v = validate(req, null);
        long id = jdbc.sql("""
                        INSERT INTO automod_rules (community_id, name, kind, pattern, threshold, applies_to, action, reason, enabled, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(c.getId(), v.name(), v.kind().name(), v.pattern(), v.threshold(), v.appliesTo(), v.action().name(),
                        v.reason(), v.enabled(), user.id())
                .query(Long.class).single();
        logRuleChange(c.getId(), user, "Created automod rule “" + v.name() + "” (" + describe(v.kind(), v.action()) + ")");
        return ruleDtos(c.getId(), id).getFirst();
    }

    @Transactional
    public RuleDto update(String slug, AuthUser user, long ruleId, RuleRequest req) {
        Community c = moderated(slug, user);
        limiter.check("automod:write:" + user.id(), 60, Duration.ofHours(1));
        RuleRequest existing = requireRule(c.getId(), ruleId);
        Valid v = validate(req, existing);
        jdbc.sql("""
                        UPDATE automod_rules SET name = ?, kind = ?, pattern = ?, threshold = ?, applies_to = ?, action = ?,
                               reason = ?, enabled = ?, updated_at = now()
                        WHERE id = ? AND community_id = ?""")
                .params(v.name(), v.kind().name(), v.pattern(), v.threshold(), v.appliesTo(), v.action().name(), v.reason(),
                        v.enabled(), ruleId, c.getId())
                .update();
        String change = Boolean.TRUE.equals(existing.enabled()) != v.enabled()
                ? (v.enabled() ? "Turned on" : "Turned off") : "Edited";
        logRuleChange(c.getId(), user, change + " automod rule “" + v.name() + "” (" + describe(v.kind(), v.action()) + ")");
        return ruleDtos(c.getId(), ruleId).getFirst();
    }

    @Transactional
    public void delete(String slug, AuthUser user, long ruleId) {
        Community c = moderated(slug, user);
        RuleRequest existing = requireRule(c.getId(), ruleId);
        jdbc.sql("DELETE FROM automod_rules WHERE id = ? AND community_id = ?").params(ruleId, c.getId()).update();
        logRuleChange(c.getId(), user, "Deleted automod rule “" + existing.name() + "”");
    }

    @Transactional(readOnly = true)
    public List<HitDto> hits(String slug, AuthUser user, int limit) {
        Community c = moderated(slug, user);
        record R(long id, Long ruleId, String name, String action, String outcome, String type, Long postId, Long commentId,
                 Long authorId, String detail, String excerpt, Instant at) {}
        List<R> rows = jdbc.sql("""
                        SELECT id, rule_id, rule_name, action, outcome, target_type, post_id, comment_id, author_id, detail,
                               excerpt, created_at
                        FROM automod_hits WHERE community_id = ? ORDER BY id DESC LIMIT ?""")
                .params(c.getId(), limit)
                .query((rs, i) -> new R(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), (Long) rs.getObject(7), (Long) rs.getObject(8), (Long) rs.getObject(9), rs.getString(10),
                        rs.getString(11), rs.getObject(12, OffsetDateTime.class).toInstant()))
                .list();
        Map<Long, UserSummary> u = directory.summaries(rows.stream().map(R::authorId).toList());
        return rows.stream().map(r -> new HitDto(r.id(), r.ruleId(), r.name(), r.action(), r.outcome(), r.type(), r.postId(),
                r.commentId(), r.authorId() == null ? null : u.get(r.authorId()), r.detail(), r.excerpt(), r.at())).toList();
    }

    // ------------------------------------------------------------------ preview ("test a rule")

    /**
     * Runs a draft rule (not saved) against the community's most recent posts and comments, including removed ones so
     * moderators can tune against what they already took down. Nothing is changed.
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(String slug, AuthUser user, RuleRequest req) {
        Community c = moderated(slug, user);
        limiter.check("automod:preview:" + user.id(), 30, Duration.ofMinutes(10));
        Valid v = validate(req, null);
        Rule rule = compile(0, v.name(), v.kind(), v.pattern(), v.threshold(), v.appliesTo(), v.action(), v.reason());
        record Item(String type, long postId, Long commentId, Long authorId, String text, String raw, int links, Instant at) {}
        List<Item> items = new ArrayList<>();
        if (rule.appliesTo("POST")) {
            items.addAll(jdbc.sql("""
                            SELECT id, author_id, title, body_text, coalesce(body_html, '') || ' ' || coalesce(url, ''), link_count, created_at
                            FROM posts WHERE community_id = ? AND deleted_at IS NULL ORDER BY id DESC LIMIT ?""")
                    .params(c.getId(), PREVIEW_POSTS)
                    .query((rs, i) -> new Item("POST", rs.getLong(1), null, (Long) rs.getObject(2),
                            rs.getString(3) + "\n" + nz(rs.getString(4)), rs.getString(5), rs.getInt(6),
                            rs.getObject(7, OffsetDateTime.class).toInstant()))
                    .list());
        }
        int posts = items.size();
        if (rule.appliesTo("COMMENT")) {
            items.addAll(jdbc.sql("""
                            SELECT c.id, c.post_id, c.author_id, c.body, c.link_count, c.created_at
                            FROM comments c JOIN posts p ON p.id = c.post_id
                            WHERE p.community_id = ? AND c.deleted_at IS NULL ORDER BY c.id DESC LIMIT ?""")
                    .params(c.getId(), PREVIEW_COMMENTS)
                    .query((rs, i) -> new Item("COMMENT", rs.getLong(2), rs.getLong(1), (Long) rs.getObject(3),
                            rs.getString(4), rs.getString(4), rs.getInt(5), rs.getObject(6, OffsetDateTime.class).toInstant()))
                    .list());
        }
        int comments = items.size() - posts;
        List<Long> authorIds = items.stream().map(Item::authorId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Author> authors = authors(authorIds);
        Set<Long> exempt = exemptAuthors(c.getId(), authorIds);
        Map<Long, UserSummary> u = directory.summaries(authorIds);
        List<PreviewMatch> matches = new ArrayList<>();
        int count = 0;
        int timedOut = 0;
        for (Item it : items) {
            Author a = it.authorId() == null ? null : authors.get(it.authorId());
            String detail = evaluate(rule, it.text(), it.raw(), it.links(), a);
            if (detail == null) continue;
            if (detail.equals(TIMED_OUT)) {
                timedOut++;
                continue;
            }
            count++;
            if (matches.size() < PREVIEW_MATCHES) {
                matches.add(new PreviewMatch(it.type(), it.postId(), it.commentId(),
                        it.authorId() == null ? null : u.get(it.authorId()),
                        AutomodMatcher.clip(it.text().strip().replaceAll("\\s+", " "), 200), detail,
                        it.authorId() != null && exempt.contains(it.authorId()), it.at()));
            }
        }
        return new PreviewResult(posts, comments, count, matches, timedOut);
    }

    // ------------------------------------------------------------------ evaluation

    private static final String TIMED_OUT = "\u0000timeout";

    private record Author(Instant createdAt, long karma) {}

    /** A short human detail when the rule matches, {@link #TIMED_OUT} when a pattern ran out of time, else null. */
    private String evaluate(Rule r, String text, String raw, int linkCount, Author a) {
        switch (r.kind()) {
            case KEYWORD, REGEX -> {
                AutomodMatcher.Result res = AutomodMatcher.find(r.compiled(), text);
                if (res.timedOut()) {
                    log.warn("Automod rule {} timed out", r.id());
                    return TIMED_OUT;
                }
                return res.matched() ? "matched “" + res.text() + "”" : null;
            }
            case LINK_LIMIT -> {
                return linkCount > r.threshold() ? linkCount + " links (limit " + r.threshold() + ")" : null;
            }
            case DOMAIN -> {
                String host = AutomodMatcher.blockedHost(AutomodMatcher.hosts(text + " " + nz(raw)), r.domains());
                return host == null ? null : "links to " + host;
            }
            case ACCOUNT_AGE -> {
                if (a == null) return null;
                long days = Duration.between(a.createdAt(), Instant.now()).toDays();
                return days < r.threshold() ? "account is " + days + " days old (minimum " + r.threshold() + ")" : null;
            }
            case KARMA -> {
                if (a == null) return null;
                return a.karma() < r.threshold() ? "karma " + a.karma() + " (minimum " + r.threshold() + ")" : null;
            }
        }
        return null;
    }

    private Author author(long id) {
        return authors(List.of(id)).get(id);
    }

    private Map<Long, Author> authors(List<Long> ids) {
        Map<Long, Author> out = new java.util.HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.sql("SELECT id, created_at, post_karma + comment_karma FROM users WHERE id IN (:ids)").param("ids", ids)
                .query((rs, i) -> out.put(rs.getLong(1), new Author(rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getLong(3))))
                .list();
        return out;
    }

    private Set<Long> exemptAuthors(long communityId, List<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.sql("""
                        SELECT u.id FROM users u WHERE u.id IN (:ids)
                          AND (u.role = 'ADMIN' OR EXISTS (SELECT 1 FROM community_members m WHERE m.community_id = :c
                               AND m.user_id = u.id AND m.role IN ('MODERATOR', 'ADMIN', 'OWNER')))""")
                .param("ids", ids).param("c", communityId).query(Long.class).list());
    }

    private List<Rule> loadRules(long communityId, boolean enabledOnly) {
        List<Rule> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT id, name, kind, pattern, threshold, applies_to, action, reason FROM automod_rules
                        WHERE community_id = ? AND (enabled OR NOT ?) ORDER BY id""")
                .params(communityId, enabledOnly)
                .query((rs, i) -> {
                    try {
                        out.add(compile(rs.getLong(1), rs.getString(2), Kind.valueOf(rs.getString(3)), rs.getString(4),
                                (Integer) rs.getObject(5), rs.getString(6), Action.valueOf(rs.getString(7)), rs.getString(8)));
                    } catch (IllegalArgumentException e) {
                        log.warn("Skipping automod rule {}: {}", rs.getLong(1), e.getMessage());
                    }
                    return null;
                })
                .list();
        return out;
    }

    private static Rule compile(long id, String name, Kind kind, String pattern, Integer threshold, String appliesTo,
                                Action action, String reason) {
        Pattern p = switch (kind) {
            case KEYWORD -> AutomodMatcher.keywords(pattern);
            case REGEX -> AutomodMatcher.regex(pattern);
            default -> null;
        };
        Set<String> domains = kind == Kind.DOMAIN ? AutomodMatcher.domains(pattern) : Set.of();
        return new Rule(id, name, kind, pattern, threshold, appliesTo, action, reason, p, domains);
    }

    // ------------------------------------------------------------------ validation

    private record Valid(String name, Kind kind, String pattern, Integer threshold, String appliesTo, Action action,
                         String reason, boolean enabled) {}

    /** Validates a full rule; on update, missing fields keep their stored values. */
    private Valid validate(RuleRequest req, RuleRequest base) {
        String name = pick(req.name(), base == null ? null : base.name());
        String kindRaw = pick(req.kind(), base == null ? null : base.kind());
        String pattern = req.pattern() != null ? req.pattern() : base == null ? null : base.pattern();
        Integer threshold = req.threshold() != null ? req.threshold() : base == null ? null : base.threshold();
        String applies = pick(req.appliesTo(), base == null ? "BOTH" : base.appliesTo());
        String actionRaw = pick(req.action(), base == null ? null : base.action());
        String reason = pick(req.reason(), base == null ? null : base.reason());
        boolean enabled = req.enabled() != null ? req.enabled() : base == null || Boolean.TRUE.equals(base.enabled());

        name = name == null ? "" : name.strip();
        if (name.isEmpty() || name.length() > 80) throw ApiException.field("name", "Name the rule (up to 80 characters)");
        Kind kind = parse(Kind.class, kindRaw, "kind", "Pick a rule type");
        Action action = parse(Action.class, actionRaw, "action", "Pick HOLD, REMOVE or FLAG");
        applies = applies == null ? "BOTH" : applies.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("POSTS", "COMMENTS", "BOTH").contains(applies)) throw ApiException.field("appliesTo", "Use POSTS, COMMENTS or BOTH");
        reason = reason == null ? "" : reason.strip();
        if (reason.isEmpty() || reason.length() > 200) {
            throw ApiException.field("reason", "Write the reason authors will see (up to 200 characters)");
        }
        switch (kind) {
            case KEYWORD, REGEX, DOMAIN -> {
                if (pattern == null || pattern.isBlank()) throw ApiException.field("pattern", "This rule needs a pattern");
                if (pattern.length() > 2000) throw ApiException.field("pattern", "Patterns can be up to 2000 characters");
                try {
                    compile(0, name, kind, pattern, null, applies, action, reason);
                } catch (IllegalArgumentException e) {
                    throw ApiException.field("pattern", e.getMessage());
                }
                threshold = null;
            }
            case LINK_LIMIT -> {
                requireRange(threshold, 0, 100, "Allow between 0 and 100 links");
                pattern = null;
            }
            case ACCOUNT_AGE -> {
                requireRange(threshold, 1, 3650, "Use a minimum age between 1 and 3650 days");
                pattern = null;
            }
            case KARMA -> {
                requireRange(threshold, 1, 1_000_000, "Use a minimum karma between 1 and 1,000,000");
                pattern = null;
            }
        }
        return new Valid(name, kind, pattern == null ? null : pattern.strip(), threshold, applies, action, reason, enabled);
    }

    private static void requireRange(Integer v, int min, int max, String message) {
        if (v == null || v < min || v > max) throw ApiException.field("threshold", message);
    }

    private static String pick(String a, String b) {
        return a != null ? a : b;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String field, String message) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.field(field, message);
        }
    }

    private static String describe(Kind kind, Action action) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ", " + action.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ helpers

    private Community moderated(String slug, AuthUser user) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        return c;
    }

    private RuleRequest requireRule(long communityId, long ruleId) {
        return jdbc.sql("SELECT name, kind, pattern, threshold, applies_to, action, reason, enabled FROM automod_rules WHERE id = ? AND community_id = ?")
                .params(ruleId, communityId)
                .query((rs, i) -> new RuleRequest(rs.getString(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8)))
                .optional().orElseThrow(() -> ApiException.notFound("Rule"));
    }

    private List<RuleDto> ruleDtos(long communityId, long onlyId) {
        record R(long id, String name, String kind, String pattern, Integer threshold, String applies, String action,
                 String reason, boolean enabled, Long createdBy, Instant createdAt, Instant updatedAt, long hits) {}
        List<R> rows = jdbc.sql("""
                        SELECT r.id, r.name, r.kind, r.pattern, r.threshold, r.applies_to, r.action, r.reason, r.enabled,
                               r.created_by, r.created_at, r.updated_at,
                               (SELECT count(*) FROM automod_hits h WHERE h.rule_id = r.id AND h.created_at > now() - interval '30 days') AS hits
                        FROM automod_rules r WHERE r.community_id = ? AND (? = 0 OR r.id = ?) ORDER BY r.id""")
                .params(communityId, onlyId, onlyId)
                .query((rs, i) -> new R(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), (Integer) rs.getObject(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getBoolean(9), (Long) rs.getObject(10),
                        rs.getObject(11, OffsetDateTime.class).toInstant(), rs.getObject(12, OffsetDateTime.class).toInstant(),
                        rs.getLong(13)))
                .list();
        Map<Long, UserSummary> u = directory.summaries(rows.stream().map(R::createdBy).toList());
        return rows.stream().map(r -> new RuleDto(r.id(), r.name(), r.kind(), r.pattern(), r.threshold(), r.applies(), r.action(),
                r.reason(), r.enabled(), r.createdBy() == null ? null : u.get(r.createdBy()), r.createdAt(), r.updatedAt(),
                r.hits())).toList();
    }

    private long logAction(long communityId, String action, Long targetUser, Long postId, Long commentId, String reason) {
        return jdbc.sql("""
                        INSERT INTO moderation_actions (community_id, moderator_id, action, target_user_id, post_id, comment_id, reason)
                        VALUES (?, NULL, ?, ?, ?, ?, ?) RETURNING id""")
                .params(communityId, action, targetUser, postId, commentId, AutomodMatcher.clip(reason, 300))
                .query(Long.class).single();
    }

    private void logRuleChange(long communityId, AuthUser mod, String reason) {
        jdbc.sql("""
                        INSERT INTO moderation_actions (community_id, moderator_id, action, reason)
                        VALUES (?, ?, 'AUTOMOD_RULE', ?)""")
                .params(communityId, mod.id(), AutomodMatcher.clip(reason, 300)).update();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

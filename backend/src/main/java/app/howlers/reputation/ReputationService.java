package app.howlers.reputation;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.post.PostService;
import app.howlers.post.PostService.PostContext;
import app.howlers.reputation.ReputationDtos.Expertise;
import app.howlers.reputation.ReputationDtos.LeaderEntry;
import app.howlers.reputation.ReputationDtos.Policy;
import app.howlers.reputation.ReputationDtos.PostExpertise;
import app.howlers.reputation.ReputationDtos.ReputationOverview;
import app.howlers.reputation.ReputationDtos.TopicLeaderboard;
import app.howlers.reputation.ReputationDtos.TopicOverview;
import app.howlers.reputation.ReputationDtos.TopicStanding;
import app.howlers.reputation.ReputationDtos.UserReputation;
import app.howlers.reputation.ReputationMath.Key;
import app.howlers.reputation.ReputationMath.Tier;
import app.howlers.reputation.ReputationMath.Totals;
import app.howlers.reputation.ReputationMath.VoteFact;
import app.howlers.reputation.ReputationMath.Voter;
import app.howlers.topic.Topic;
import app.howlers.topic.Topic.TopicDto;
import app.howlers.topic.TopicRepository;
import app.howlers.user.User;
import app.howlers.user.UserDirectory;
import app.howlers.user.UserRepository;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Topic reputation: recompute (full or incremental) from vote rows, and the read models behind badges,
 * leaderboards and the expertise chip. See {@link ReputationMath} for the rules and D-020 for the reasoning.
 */
@Service
public class ReputationService {
    private static final long LOCK_KEY = 0x52455055L; // "REPU"
    private static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TopicRepository topics;
    private final UserRepository users;
    private final UserDirectory directory;
    private final PostService posts;

    public ReputationService(JdbcClient jdbc, JdbcTemplate template, TopicRepository topics, UserRepository users,
                             UserDirectory directory, PostService posts) {
        this.jdbc = jdbc;
        this.template = template;
        this.topics = topics;
        this.users = users;
        this.directory = directory;
        this.posts = posts;
    }

    // ------------------------------------------------------------------ recompute

    public record RunResult(int authors, int rows) {}

    /** Rebuilds every total from the vote tables. Deterministic: same vote rows, same totals. */
    @Transactional
    public RunResult rebuildAll() {
        lock();
        Instant started = jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
        List<VoteFact> facts = loadFacts(null);
        Map<Long, Voter> voters = loadVoters(null);
        Map<Key, Totals> totals = ReputationMath.computeTwoPass(facts, voters);
        Map<Key, int[]> taking = loadParticipation(null);
        jdbc.sql("DELETE FROM topic_reputation").update();
        int rows = write(totals, taking);
        jdbc.sql("DELETE FROM reputation_dirty WHERE queued_at <= ?").param(started.atOffset(java.time.ZoneOffset.UTC)).update();
        Set<Long> everyone = new HashSet<>();
        totals.keySet().forEach(k -> everyone.add(k.userId()));
        taking.keySet().forEach(k -> everyone.add(k.userId()));
        int authors = everyone.size();
        jdbc.sql("INSERT INTO reputation_runs (kind, authors, rows_written) VALUES ('FULL', ?, ?)").params(authors, rows).update();
        return new RunResult(authors, rows);
    }

    /**
     * Recomputes the authors marked dirty since the last run. Voter weights come from the stored unweighted base,
     * so this is cheap; the nightly {@link #rebuildAll()} refreshes those bases everywhere.
     */
    @Transactional
    public RunResult processDirty(int max) {
        lock();
        List<Long> authors = jdbc.sql("""
                        DELETE FROM reputation_dirty WHERE user_id IN (
                          SELECT user_id FROM reputation_dirty ORDER BY queued_at, user_id LIMIT ? FOR UPDATE SKIP LOCKED)
                        RETURNING user_id""")
                .param(max).query(Long.class).list();
        if (authors.isEmpty()) return new RunResult(0, 0);
        List<VoteFact> facts = loadFacts(authors);
        Set<Long> voterIds = new HashSet<>();
        facts.forEach(f -> voterIds.add(f.voterId()));
        Map<Long, Voter> voters = loadVoters(voterIds);
        Map<Key, Double> bases = new HashMap<>();
        for (List<Long> chunk : chunks(voterIds)) {
            jdbc.sql("SELECT user_id, topic_id, base_points FROM topic_reputation WHERE user_id IN (:ids)").param("ids", chunk)
                    .query((rs, i) -> bases.put(new Key(rs.getLong(1), rs.getLong(2)), rs.getDouble(3))).list();
        }
        Map<Key, Totals> totals = ReputationMath.compute(facts, voters,
                (v, t) -> Math.max(0, bases.getOrDefault(new Key(v, t), 0.0)));
        Map<Key, int[]> taking = loadParticipation(authors);
        for (List<Long> chunk : chunks(authors)) {
            jdbc.sql("DELETE FROM topic_reputation WHERE user_id IN (:ids)").param("ids", chunk).update();
        }
        int rows = write(totals, taking);
        jdbc.sql("INSERT INTO reputation_runs (kind, authors, rows_written) VALUES ('INCREMENTAL', ?, ?)").params(authors.size(), rows).update();
        return new RunResult(authors.size(), rows);
    }

    private void lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(LOCK_KEY).query(Object.class).list();
    }

    /** Topic points for taking part: per published post and per published comment (votes add on top). */
    public static final int POST_POINTS = 2;
    public static final int COMMENT_POINTS = 1;

    private int write(Map<Key, Totals> totals, Map<Key, int[]> taking) {
        Set<Key> keys = new java.util.LinkedHashSet<>(totals.keySet());
        keys.addAll(taking.keySet());
        List<Object[]> args = new ArrayList<>();
        for (Key k : keys) {
            Totals t = totals.get(k);
            int[] pc = taking.getOrDefault(k, new int[2]);
            int up = t == null ? 0 : t.up(), down = t == null ? 0 : t.down(), capped = t == null ? 0 : t.capped();
            if (up + down + capped + pc[0] + pc[1] == 0) continue;
            double points = (t == null ? 0 : t.points()) + POST_POINTS * pc[0] + COMMENT_POINTS * pc[1];
            args.add(new Object[]{k.userId(), k.topicId(), points, t == null ? 0 : t.base(), up, down, capped, pc[0], pc[1]});
        }
        if (args.isEmpty()) return 0;
        template.batchUpdate("""
                INSERT INTO topic_reputation (user_id, topic_id, points, base_points, upvotes_counted, downvotes_counted,
                                              votes_capped, posts_counted, comments_counted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", args);
        return args.size();
    }

    private static final String PARTICIPATION = """
            SELECT 'P' AS kind, p.author_id AS author, p.tags AS tags, c.topic_id AS topic_id
            FROM posts p JOIN communities c ON c.id = p.community_id
            WHERE p.author_id IS NOT NULL AND p.deleted_at IS NULL AND p.removed = FALSE AND p.pending = FALSE
              AND c.visibility <> 'PRIVATE' AND p.author_id IN (:ids)
            UNION ALL
            SELECT 'C', cm.author_id, p.tags, c.topic_id
            FROM comments cm JOIN posts p ON p.id = cm.post_id JOIN communities c ON c.id = p.community_id
            WHERE cm.author_id IS NOT NULL AND cm.deleted_at IS NULL AND cm.removed = FALSE AND cm.pending = FALSE
              AND p.deleted_at IS NULL AND c.visibility <> 'PRIVATE' AND cm.author_id IN (:ids)
            """;

    /** Published posts and comments per (author, topic), for {@code authors} or everyone when null. */
    Map<Key, int[]> loadParticipation(Collection<Long> authors) {
        Map<String, Long> topicBySlug = topicSlugs();
        Map<Key, int[]> out = new HashMap<>();
        Collection<Long> ids = authors;
        if (ids == null) ids = jdbc.sql("SELECT id FROM users WHERE deleted_at IS NULL").query(Long.class).list();
        for (List<Long> chunk : chunks(ids)) {
            jdbc.sql(PARTICIPATION).param("ids", chunk).query((rs, i) -> {
                boolean post = rs.getString("kind").charAt(0) == 'P';
                long author = rs.getLong("author");
                for (long topic : topicsOf(rs, topicBySlug)) {
                    out.computeIfAbsent(new Key(author, topic), k -> new int[2])[post ? 0 : 1]++;
                }
                return null;
            }).list();
        }
        return out;
    }

    /** The community's topic plus any post tag that names a topic; General when there is neither. */
    private static long[] topicsOf(ResultSet rs, Map<String, Long> topicBySlug) throws SQLException {
        Set<Long> t = new LinkedHashSet<>();
        long communityTopic = rs.getLong("topic_id");
        if (!rs.wasNull()) t.add(communityTopic);
        for (String tag : tags(rs.getArray("tags"))) {
            Long id = topicBySlug.get(tag);
            if (id != null) t.add(id);
        }
        Long general = topicBySlug.get("general");
        if (t.isEmpty() && general != null) t.add(general);
        return t.stream().mapToLong(Long::longValue).toArray();
    }

    private static final String POST_FACTS = """
            SELECT 'P' AS kind, v.post_id AS target, v.user_id AS voter, p.author_id AS author, v.value AS value,
                   v.updated_at AS at, p.removed AS removed, p.tags AS tags, c.topic_id AS topic_id
            FROM post_votes v
            JOIN posts p ON p.id = v.post_id
            JOIN communities c ON c.id = p.community_id
            WHERE p.author_id IS NOT NULL AND c.visibility <> 'PRIVATE'
            """;
    private static final String COMMENT_FACTS = """
            SELECT 'C' AS kind, v.comment_id AS target, v.user_id AS voter, cm.author_id AS author, v.value AS value,
                   v.updated_at AS at, cm.removed AS removed, p.tags AS tags, c.topic_id AS topic_id
            FROM comment_votes v
            JOIN comments cm ON cm.id = v.comment_id
            JOIN posts p ON p.id = cm.post_id
            JOIN communities c ON c.id = p.community_id
            WHERE cm.author_id IS NOT NULL AND c.visibility <> 'PRIVATE'
            """;

    /** Votes received by {@code authors} (or by everyone when null). Private communities never earn public reputation. */
    List<VoteFact> loadFacts(Collection<Long> authors) {
        Map<String, Long> topicBySlug = topicSlugs();
        List<VoteFact> out = new ArrayList<>();
        if (authors == null) {
            jdbc.sql(POST_FACTS).query((rs, i) -> out.add(fact(rs, topicBySlug))).list();
            jdbc.sql(COMMENT_FACTS).query((rs, i) -> out.add(fact(rs, topicBySlug))).list();
            return out;
        }
        for (List<Long> chunk : chunks(authors)) {
            jdbc.sql(POST_FACTS + " AND p.author_id IN (:ids)").param("ids", chunk).query((rs, i) -> out.add(fact(rs, topicBySlug))).list();
            jdbc.sql(COMMENT_FACTS + " AND cm.author_id IN (:ids)").param("ids", chunk).query((rs, i) -> out.add(fact(rs, topicBySlug))).list();
        }
        return out;
    }

    private Map<String, Long> topicSlugs() {
        Map<String, Long> m = new HashMap<>();
        jdbc.sql("SELECT slug, id FROM topics").query((rs, i) -> m.put(rs.getString(1), rs.getLong(2))).list();
        return m;
    }

    private static VoteFact fact(ResultSet rs, Map<String, Long> topicBySlug) throws SQLException {
        return new VoteFact(rs.getString("kind").charAt(0), rs.getLong("target"), rs.getLong("voter"), rs.getLong("author"),
                rs.getInt("value"), rs.getObject("at", OffsetDateTime.class).toInstant(), rs.getBoolean("removed"),
                topicsOf(rs, topicBySlug));
    }

    private static List<String> tags(Array a) throws SQLException {
        if (a == null) return List.of();
        Object[] arr = (Object[]) a.getArray();
        List<String> out = new ArrayList<>(arr.length);
        for (Object o : arr) if (o != null) out.add(o.toString().toLowerCase());
        return out;
    }

    private static final String VOTERS = """
            SELECT id, created_at, post_karma + comment_karma AS karma,
                   status NOT IN ('SUSPENDED', 'DELETED') AND deleted_at IS NULL AS active
            FROM users
            """;

    private Map<Long, Voter> loadVoters(Collection<Long> ids) {
        Map<Long, Voter> out = new HashMap<>();
        if (ids == null) {
            jdbc.sql(VOTERS).query((rs, i) -> out.put(rs.getLong(1), voter(rs))).list();
            return out;
        }
        for (List<Long> chunk : chunks(ids)) {
            jdbc.sql(VOTERS + " WHERE id IN (:ids)").param("ids", chunk).query((rs, i) -> out.put(rs.getLong(1), voter(rs))).list();
        }
        return out;
    }

    private static Voter voter(ResultSet rs) throws SQLException {
        return new Voter(rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getInt(3), rs.getBoolean(4));
    }

    private static <T> List<List<T>> chunks(Collection<T> all) {
        List<T> list = new ArrayList<>(all);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += BATCH) out.add(list.subList(i, Math.min(list.size(), i + BATCH)));
        return out;
    }

    // ------------------------------------------------------------------ reads

    private Instant lastRun() {
        // max() over no rows is NULL, and single() rejects a null result, so map to an Optional first.
        return jdbc.sql("SELECT max(finished_at) FROM reputation_runs")
                .query((rs, i) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)).map(OffsetDateTime::toInstant))
                .single().orElse(null);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Points below 1 aren't listed: they're mostly single discounted votes and add noise, not signal. */
    private static final double LIST_MIN = 1.0;

    @Transactional(readOnly = true)
    public TopicLeaderboard leaders(String slug, int limit) {
        Topic topic = topics.findBySlug(slug.toLowerCase()).orElseThrow(() -> ApiException.notFound("Topic"));
        record R(long userId, double points, int up, int down, int posts, int comments) {}
        List<R> rows = jdbc.sql("""
                        SELECT r.user_id, r.points, r.upvotes_counted, r.downvotes_counted, r.posts_counted, r.comments_counted
                        FROM topic_reputation r JOIN users u ON u.id = r.user_id
                        WHERE r.topic_id = ? AND r.points >= ? AND u.deleted_at IS NULL
                        ORDER BY r.points DESC, r.user_id LIMIT ?""")
                .params(topic.getId(), LIST_MIN, Math.max(1, Math.min(limit, 100)))
                .query((rs, i) -> new R(rs.getLong(1), rs.getDouble(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6))).list();
        long contributors = jdbc.sql("""
                        SELECT count(*) FROM topic_reputation r JOIN users u ON u.id = r.user_id
                        WHERE r.topic_id = ? AND r.points >= ? AND u.deleted_at IS NULL""")
                .params(topic.getId(), LIST_MIN).query(Long.class).single();
        Map<Long, UserSummary> u = directory.summaries(rows.stream().map(R::userId).toList());
        List<LeaderEntry> entries = new ArrayList<>();
        int rank = 0;
        for (R r : rows) {
            rank++;
            if (!u.containsKey(r.userId())) continue;
            entries.add(new LeaderEntry(rank, u.get(r.userId()), round1(r.points()), Tier.of(r.points()).name(),
                    rank <= ReputationMath.TOP_RANK && r.points() >= ReputationMath.TOP_MIN_POINTS, r.up(), r.down(),
                    r.posts(), r.comments()));
        }
        return new TopicLeaderboard(TopicDto.of(topic), entries, contributors, lastRun(), Policy.current());
    }

    @Transactional(readOnly = true)
    public ReputationOverview overview() {
        record R(long topicId, long userId, double points, int up, int down, int rn, long n, int posts, int comments) {}
        List<R> rows = jdbc.sql("""
                        SELECT topic_id, user_id, points, upvotes_counted, downvotes_counted, rn, n, posts_counted, comments_counted FROM (
                          SELECT r.topic_id, r.user_id, r.points, r.upvotes_counted, r.downvotes_counted,
                                 r.posts_counted, r.comments_counted,
                                 row_number() OVER (PARTITION BY r.topic_id ORDER BY r.points DESC, r.user_id) AS rn,
                                 count(*) OVER (PARTITION BY r.topic_id) AS n
                          FROM topic_reputation r JOIN users u ON u.id = r.user_id
                          WHERE r.points >= ? AND u.deleted_at IS NULL) s
                        WHERE rn <= 3""")
                .param(LIST_MIN)
                .query((rs, i) -> new R(rs.getLong(1), rs.getLong(2), rs.getDouble(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                        rs.getLong(7), rs.getInt(8), rs.getInt(9)))
                .list();
        Map<Long, UserSummary> u = directory.summaries(rows.stream().map(R::userId).toList());
        List<TopicOverview> out = new ArrayList<>();
        for (Topic t : topics.findAllByOrderByNameAsc()) {
            List<R> mine = rows.stream().filter(r -> r.topicId() == t.getId()).sorted(Comparator.comparingInt(R::rn)).toList();
            List<LeaderEntry> leaders = mine.stream().filter(r -> u.containsKey(r.userId()))
                    .map(r -> new LeaderEntry(r.rn(), u.get(r.userId()), round1(r.points()), Tier.of(r.points()).name(),
                            r.points() >= ReputationMath.TOP_MIN_POINTS, r.up(), r.down(), r.posts(), r.comments()))
                    .toList();
            out.add(new TopicOverview(TopicDto.of(t), mine.isEmpty() ? 0 : mine.getFirst().n(), leaders));
        }
        return new ReputationOverview(out, lastRun());
    }

    @Transactional(readOnly = true)
    public UserReputation forUser(String username, AuthUser viewer) {
        User user = users.findByUsernameIgnoreCase(username).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("User"));
        boolean self = viewer != null && viewer.id() == user.getId();
        if (!user.isProfilePublic() && !self) return new UserReputation(true, List.of(), null);
        record R(long topicId, double points, int up, int down, int rank, int posts, int comments) {}
        List<R> rows = jdbc.sql("""
                        SELECT r.topic_id, r.points, r.upvotes_counted, r.downvotes_counted,
                               1 + (SELECT count(*) FROM topic_reputation o JOIN users ou ON ou.id = o.user_id
                                    WHERE o.topic_id = r.topic_id AND ou.deleted_at IS NULL
                                      AND (o.points > r.points OR (o.points = r.points AND o.user_id < r.user_id))) AS rank,
                               r.posts_counted, r.comments_counted
                        FROM topic_reputation r WHERE r.user_id = ? AND r.points >= ?
                        ORDER BY r.points DESC, r.topic_id""")
                .params(user.getId(), LIST_MIN)
                .query((rs, i) -> new R(rs.getLong(1), rs.getDouble(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7))).list();
        Map<Long, Topic> byId = new HashMap<>();
        topics.findAll().forEach(t -> byId.put(t.getId(), t));
        List<TopicStanding> out = rows.stream().filter(r -> byId.containsKey(r.topicId()))
                .map(r -> new TopicStanding(TopicDto.of(byId.get(r.topicId())), round1(r.points()), Tier.of(r.points()).name(), r.rank(),
                        r.rank() <= ReputationMath.TOP_RANK && r.points() >= ReputationMath.TOP_MIN_POINTS, r.up(), r.down(),
                        r.posts(), r.comments()))
                .toList();
        return new UserReputation(false, out, lastRun());
    }

    /**
     * Expertise chips for one thread: a fixed number of queries per page (post topics, authors, their standing in
     * those topics, and the top-N cut-off per topic), never one per comment.
     */
    @Transactional(readOnly = true)
    public PostExpertise forPost(long postId, AuthUser viewer) {
        PostContext ctx = posts.context(postId, viewer);
        if (ctx.post().getDeletedAt() != null) throw ApiException.notFound("Post");
        Set<Long> topicIds = new LinkedHashSet<>();
        if (ctx.community().getTopic() != null) topicIds.add(ctx.community().getTopic().getId());
        Map<String, Long> bySlug = topicSlugs();
        for (String tag : ctx.post().getTags()) {
            Long id = bySlug.get(tag.toLowerCase());
            if (id != null) topicIds.add(id);
        }
        if (topicIds.isEmpty()) return new PostExpertise(List.of(), List.of());
        Map<Long, Topic> topicById = new LinkedHashMap<>();
        topics.findAllById(topicIds).forEach(t -> topicById.put(t.getId(), t));

        List<Long> authors = jdbc.sql("""
                        SELECT author_id FROM posts WHERE id = :p AND author_id IS NOT NULL
                        UNION SELECT DISTINCT author_id FROM comments WHERE post_id = :p AND author_id IS NOT NULL""")
                .param("p", postId).query(Long.class).list();
        if (authors.isEmpty()) return new PostExpertise(topicById.values().stream().map(TopicDto::of).toList(), List.of());

        // Top-N per topic: a short index scan each (≤ 6 topics per post: the community's plus ≤ 5 tags).
        Map<Long, Set<Long>> topUsers = new HashMap<>();
        for (long t : topicById.keySet()) {
            topUsers.put(t, new HashSet<>(jdbc.sql("""
                            SELECT r.user_id FROM topic_reputation r JOIN users u ON u.id = r.user_id
                            WHERE r.topic_id = ? AND r.points >= ? AND u.deleted_at IS NULL
                            ORDER BY r.points DESC, r.user_id LIMIT ?""")
                    .params(t, ReputationMath.TOP_MIN_POINTS, ReputationMath.TOP_RANK).query(Long.class).list()));
        }
        record R(long userId, long topicId, double points) {}
        List<R> rows = new ArrayList<>();
        for (List<Long> chunk : chunks(authors)) {
            rows.addAll(jdbc.sql("SELECT user_id, topic_id, points FROM topic_reputation WHERE user_id IN (:u) AND topic_id IN (:t)")
                    .param("u", chunk).param("t", topicById.keySet())
                    .query((rs, i) -> new R(rs.getLong(1), rs.getLong(2), rs.getDouble(3))).list());
        }
        Map<Long, Expertise> best = new LinkedHashMap<>();
        rows.sort(Comparator.comparingDouble(R::points).reversed().thenComparingLong(R::topicId));
        for (R r : rows) {
            boolean top = topUsers.getOrDefault(r.topicId(), Set.of()).contains(r.userId());
            if (best.containsKey(r.userId()) || !ReputationMath.showsExpertise(r.points(), top)) continue;
            Topic t = topicById.get(r.topicId());
            best.put(r.userId(), new Expertise(r.userId(), t.getSlug(), t.getName(), round1(r.points()), Tier.of(r.points()).name(), top));
        }
        return new PostExpertise(topicById.values().stream().map(TopicDto::of).toList(),
                best.values().stream().filter(Objects::nonNull).toList());
    }
}

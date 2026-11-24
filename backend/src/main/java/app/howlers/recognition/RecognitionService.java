package app.howlers.recognition;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityMapper;
import app.howlers.community.CommunityRepository;
import app.howlers.community.CommunityRole;
import app.howlers.community.MembershipDao;
import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.recognition.ModeratorScoreMath.Result;
import app.howlers.recognition.RecognitionDtos.BadgeDto;
import app.howlers.recognition.RecognitionDtos.Components;
import app.howlers.recognition.RecognitionDtos.Formula;
import app.howlers.recognition.RecognitionDtos.Inputs;
import app.howlers.recognition.RecognitionDtos.Leaderboard;
import app.howlers.recognition.RecognitionDtos.ModerationRecord;
import app.howlers.recognition.RecognitionDtos.ModeratorScore;
import app.howlers.recognition.RecognitionDtos.ModeratorSettings;
import app.howlers.recognition.RecognitionDtos.Peer;
import app.howlers.recognition.RecognitionDtos.Peers;
import app.howlers.user.User;
import app.howlers.user.UserDirectory;
import app.howlers.user.UserRepository;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moderator recognition: computes quality-weighted scores per (community, moderator, month) from real moderation
 * rows, and serves leaderboards, profile records, peer endorsements and the ranking opt-out. See
 * {@link ModeratorScoreMath} for the formula and D-020 for the reasoning.
 */
@Service
public class RecognitionService {
    private static final long LOCK_KEY = 0x4D4F4453L; // "MODS"
    private static final String MOD_ROLES = "('OWNER','ADMIN','MODERATOR')";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final MembershipDao memberships;
    private final UserRepository users;
    private final UserDirectory directory;
    private final RateLimiter limiter;

    public RecognitionService(JdbcClient jdbc, JdbcTemplate template, CommunityRepository communities, CommunityAccess access,
                              MembershipDao memberships, UserRepository users, UserDirectory directory, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.template = template;
        this.communities = communities;
        this.access = access;
        this.memberships = memberships;
        this.users = users;
        this.directory = directory;
        this.limiter = limiter;
    }

    public static YearMonth currentPeriod() {
        return YearMonth.now(ZoneOffset.UTC);
    }

    static YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) return currentPeriod();
        try {
            if (!period.matches("\\d{4}-\\d{2}")) throw new DateTimeParseException("bad", period, 0);
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw ApiException.field("period", "Use a month like 2026-11");
        }
    }

    // ------------------------------------------------------------------ compute

    private record Pair(long community, long moderator) {}

    /** Recomputes one period for every community ({@code communityId == 0}) or just one. Returns rows written. */
    @Transactional
    public int recompute(YearMonth period, long communityId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(LOCK_KEY).query(Object.class).list();
        OffsetDateTime s = period.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime e = s.plusMonths(1);
        OffsetDateTime ps = s.minusMonths(1);
        String p = period.toString();

        Map<Long, Set<Long>> roster = new HashMap<>();
        jdbc.sql("SELECT community_id, user_id FROM community_members WHERE role IN " + MOD_ROLES
                        + " AND (:cid = 0 OR community_id = :cid)")
                .param("cid", communityId)
                .query((rs, i) -> roster.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2))).list();

        Map<Pair, int[]> decisions = new HashMap<>();
        jdbc.sql("""
                        SELECT a.community_id, a.moderator_id, count(*),
                               count(*) FILTER (WHERE a.reversed_at IS NOT NULL AND (a.reversed_by IS DISTINCT FROM a.moderator_id
                                   OR EXISTS (SELECT 1 FROM appeals ap WHERE ap.action_id = a.id AND ap.status = 'ACCEPTED')))
                        FROM moderation_actions a
                        WHERE a.moderator_id IS NOT NULL AND a.action IN ('REMOVE_POST', 'REMOVE_COMMENT', 'BAN')
                          AND a.created_at >= :s AND a.created_at < :e AND (:cid = 0 OR a.community_id = :cid)
                        GROUP BY 1, 2""")
                .param("s", s).param("e", e).param("cid", communityId)
                .query((rs, i) -> decisions.put(pair(rs), new int[]{rs.getInt(3), rs.getInt(4)})).list();

        record Handled(int n, Double median) {}
        Map<Pair, Handled> handled = new HashMap<>();
        jdbc.sql("""
                        SELECT community_id, resolved_by, count(*),
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (resolved_at - created_at)) / 60.0)
                        FROM reports
                        WHERE status = 'RESOLVED' AND resolved_by IS NOT NULL AND resolved_at >= :s AND resolved_at < :e
                          AND (:cid = 0 OR community_id = :cid)
                        GROUP BY 1, 2""")
                .param("s", s).param("e", e).param("cid", communityId)
                .query((rs, i) -> handled.put(pair(rs), new Handled(rs.getInt(3), rs.getDouble(4)))).list();

        Map<Pair, int[]> appeals = new HashMap<>();
        jdbc.sql("""
                        SELECT m.community_id, m.moderator_id,
                               count(*) FILTER (WHERE ap.status = 'REJECTED'), count(*) FILTER (WHERE ap.status = 'ACCEPTED')
                        FROM appeals ap JOIN moderation_actions m ON m.id = ap.action_id
                        WHERE m.moderator_id IS NOT NULL AND ap.resolved_at >= :s AND ap.resolved_at < :e
                          AND (:cid = 0 OR m.community_id = :cid)
                        GROUP BY 1, 2""")
                .param("s", s).param("e", e).param("cid", communityId)
                .query((rs, i) -> appeals.put(pair(rs), new int[]{rs.getInt(3), rs.getInt(4)})).list();

        Map<Pair, Integer> endorsements = new HashMap<>();
        jdbc.sql("""
                        SELECT community_id, moderator_id, count(DISTINCT endorser_id) FROM moderator_endorsements
                        WHERE period = :p AND (:cid = 0 OR community_id = :cid) GROUP BY 1, 2""")
                .param("p", p).param("cid", communityId)
                .query((rs, i) -> endorsements.put(pair(rs), rs.getInt(3))).list();

        Set<Pair> candidates = new LinkedHashSet<>();
        roster.forEach((c, mods) -> mods.forEach(m -> candidates.add(new Pair(c, m))));
        candidates.addAll(decisions.keySet());
        candidates.addAll(handled.keySet());
        candidates.addAll(endorsements.keySet());
        candidates.removeIf(x -> !decisions.containsKey(x) && !handled.containsKey(x) && !endorsements.containsKey(x));

        jdbc.sql("DELETE FROM moderator_scores WHERE period = :p AND (:cid = 0 OR community_id = :cid)")
                .param("p", p).param("cid", communityId).update();
        if (candidates.isEmpty()) return 0;

        Set<Long> cids = new HashSet<>();
        candidates.forEach(x -> cids.add(x.community()));
        Map<Long, int[]> reports = new HashMap<>();
        jdbc.sql("""
                        SELECT community_id, count(*), count(*) FILTER (WHERE status = 'RESOLVED') FROM reports
                        WHERE created_at >= :s AND created_at < :e AND community_id IN (:ids) GROUP BY 1""")
                .param("s", s).param("e", e).param("ids", cids)
                .query((rs, i) -> reports.put(rs.getLong(1), new int[]{rs.getInt(2), rs.getInt(3)})).list();
        Map<Long, Double> rate = removalRates(cids, s, e);
        Map<Long, Double> prevRate = removalRates(cids, ps, s);

        List<Object[]> rows = new ArrayList<>();
        for (Pair x : candidates) {
            int[] d = decisions.getOrDefault(x, new int[2]);
            Handled h = handled.get(x);
            int[] a = appeals.getOrDefault(x, new int[2]);
            int[] r = reports.getOrDefault(x.community(), new int[2]);
            Set<Long> mods = roster.getOrDefault(x.community(), Set.of());
            int coMods = mods.size() - (mods.contains(x.moderator()) ? 1 : 0);
            ModeratorScoreMath.Inputs in = new ModeratorScoreMath.Inputs(d[0], d[1], h == null ? 0 : h.n(), h == null ? null : h.median(),
                    a[0], a[1], endorsements.getOrDefault(x, 0), coMods, r[0], r[1], rate.get(x.community()), prevRate.get(x.community()));
            Result res = ModeratorScoreMath.score(in);
            rows.add(new Object[]{x.community(), x.moderator(), p, in.decisions(), in.reversed(), in.reportsHandled(),
                    in.medianResponseMinutes(), in.appealsUpheld(), in.appealsOverturned(), in.endorsements(), in.coModerators(),
                    in.communityReports(), in.communityReportsResolved(), in.removalRate(), in.prevRemovalRate(),
                    res.consistency(), res.responsiveness(), res.appealQuality(), res.health(), res.peer(), res.score(),
                    res.activity(), String.join(",", res.badges())});
        }
        template.batchUpdate("""
                INSERT INTO moderator_scores (community_id, moderator_id, period, decisions, decisions_reversed, reports_handled,
                    median_response_minutes, appeals_upheld, appeals_overturned, endorsements, co_moderators, community_reports,
                    community_reports_resolved, removal_rate, prev_removal_rate, consistency, responsiveness, appeal_quality,
                    health, peer, score, activity, badges)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce(string_to_array(nullif(?, ''), ','), '{}'::text[]))""", rows);
        return rows.size();
    }

    private static Pair pair(ResultSet rs) throws SQLException {
        return new Pair(rs.getLong(1), rs.getLong(2));
    }

    /** Removals still standing per new post or comment in [s, e); null when the community had neither. */
    private Map<Long, Double> removalRates(Set<Long> cids, OffsetDateTime s, OffsetDateTime e) {
        Map<Long, Double> out = new HashMap<>();
        jdbc.sql("""
                        SELECT c.id,
                          (SELECT count(*) FROM moderation_actions a WHERE a.community_id = c.id
                             AND a.action IN ('REMOVE_POST', 'REMOVE_COMMENT') AND a.reversed_at IS NULL
                             AND a.created_at >= :s AND a.created_at < :e) AS removals,
                          (SELECT count(*) FROM posts p WHERE p.community_id = c.id AND p.created_at >= :s AND p.created_at < :e)
                          + (SELECT count(*) FROM comments cm JOIN posts p ON p.id = cm.post_id
                             WHERE p.community_id = c.id AND cm.created_at >= :s AND cm.created_at < :e) AS content
                        FROM communities c WHERE c.id IN (:ids)""")
                .param("s", s).param("e", e).param("ids", cids)
                .query((rs, i) -> {
                    long removals = rs.getLong(2), content = rs.getLong(3);
                    if (removals > 0 || content > 0) out.put(rs.getLong(1), (double) removals / Math.max(1, content));
                    return null;
                }).list();
        return out;
    }

    // ------------------------------------------------------------------ reads

    private static final String SCORE_COLS = """
            s.community_id, s.moderator_id, s.period, s.decisions, s.decisions_reversed, s.reports_handled,
            s.median_response_minutes, s.appeals_upheld, s.appeals_overturned, s.endorsements, s.co_moderators,
            s.community_reports, s.community_reports_resolved, s.removal_rate, s.prev_removal_rate, s.consistency,
            s.responsiveness, s.appeal_quality, s.health, s.peer, s.score, s.activity, s.badges, s.computed_at,
            coalesce(ms.public_ranking, TRUE) AS public_ranking""";

    private record Row(long communityId, long moderatorId, String period, Inputs inputs, Components components, double score,
                       int activity, List<String> badges, Instant computedAt, boolean publicRanking, Integer rank) {}

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Row row(ResultSet rs, Integer rank) throws SQLException {
        java.sql.Array arr = rs.getArray("badges");
        List<String> badges = arr == null ? List.of() : Arrays.stream((Object[]) arr.getArray()).map(Object::toString).toList();
        return new Row(rs.getLong("community_id"), rs.getLong("moderator_id"), rs.getString("period"),
                new Inputs(rs.getInt("decisions"), rs.getInt("decisions_reversed"), rs.getInt("reports_handled"),
                        dbl(rs, "median_response_minutes"), rs.getInt("appeals_upheld"), rs.getInt("appeals_overturned"),
                        rs.getInt("endorsements"), rs.getInt("co_moderators"), rs.getInt("community_reports"),
                        rs.getInt("community_reports_resolved"), dbl(rs, "removal_rate"), dbl(rs, "prev_removal_rate")),
                new Components(dbl(rs, "consistency"), dbl(rs, "responsiveness"), dbl(rs, "appeal_quality"), dbl(rs, "health"),
                        dbl(rs, "peer")),
                rs.getDouble("score"), rs.getInt("activity"), badges, rs.getObject("computed_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("public_ranking"), rank);
    }

    private List<ModeratorScore> toDtos(List<Row> rows) {
        Map<Long, UserSummary> u = directory.summaries(rows.stream().map(Row::moderatorId).toList());
        Map<Long, CommunityRef> c = new HashMap<>();
        communities.findAllById(rows.stream().map(Row::communityId).distinct().toList())
                .forEach(x -> c.put(x.getId(), CommunityMapper.ref(x)));
        return rows.stream().filter(r -> u.containsKey(r.moderatorId()) && c.containsKey(r.communityId()))
                .map(r -> new ModeratorScore(u.get(r.moderatorId()), c.get(r.communityId()), r.period(), r.rank(), r.score(),
                        r.rank() != null, r.components(), r.inputs(), r.badges().stream().map(BadgeDto::of).toList(), r.computedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Leaderboard leaderboard(String periodParam, String slug, int limit, AuthUser viewer) {
        YearMonth period = parsePeriod(periodParam);
        Community community = null;
        if (slug != null && !slug.isBlank()) {
            community = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
            access.requireView(community, viewer);
        }
        long cid = community == null ? 0 : community.getId();
        List<Row> rows = new ArrayList<>();
        int[] rank = {0};
        jdbc.sql("SELECT " + SCORE_COLS + """

                        FROM moderator_scores s
                        JOIN communities c ON c.id = s.community_id
                        JOIN users u ON u.id = s.moderator_id
                        LEFT JOIN moderator_settings ms ON ms.user_id = s.moderator_id
                        WHERE s.period = :p AND s.activity >= :min AND coalesce(ms.public_ranking, TRUE) AND u.deleted_at IS NULL
                          AND (:cid = 0 AND c.visibility <> 'PRIVATE' OR c.id = :cid)
                        ORDER BY s.score DESC, s.moderator_id, s.community_id
                        LIMIT :lim""")
                .param("p", period.toString()).param("min", ModeratorScoreMath.MIN_ACTIVITY).param("cid", cid)
                .param("lim", Math.max(1, Math.min(limit, 100)))
                .query((rs, i) -> rows.add(row(rs, ++rank[0]))).list();
        Instant computed = jdbc.sql("SELECT max(computed_at) FROM moderator_scores WHERE period = ?").param(period.toString())
                .query((rs, i) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null))
                .single();
        return new Leaderboard(period.toString(), periods(), community == null ? null : CommunityMapper.ref(community),
                toDtos(rows), Formula.current(), computed);
    }

    /** Periods that have scores, newest first, always including the current month. */
    private List<String> periods() {
        Set<String> out = new LinkedHashSet<>();
        out.add(currentPeriod().toString());
        out.addAll(jdbc.sql("SELECT DISTINCT period FROM moderator_scores ORDER BY period DESC LIMIT 12").query(String.class).list());
        return out.stream().sorted((a, b) -> b.compareTo(a)).toList();
    }

    private boolean publicRanking(long userId) {
        return jdbc.sql("SELECT public_ranking FROM moderator_settings WHERE user_id = ?").param(userId)
                .query(Boolean.class).optional().orElse(true);
    }

    @Transactional(readOnly = true)
    public ModerationRecord record(String username, AuthUser viewer) {
        User user = users.findByUsernameIgnoreCase(username).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("User"));
        boolean self = viewer != null && viewer.id() == user.getId();
        boolean optedOut = !publicRanking(user.getId());
        if (!self && (optedOut || !user.isProfilePublic())) return new ModerationRecord(true, false, List.of(), Formula.current());
        List<Row> rows = new ArrayList<>();
        jdbc.sql("SELECT " + SCORE_COLS + """
                        ,
                          CASE WHEN s.activity >= :min AND coalesce(ms.public_ranking, TRUE) THEN 1 + (
                            SELECT count(*) FROM moderator_scores o
                            JOIN users ou ON ou.id = o.moderator_id
                            LEFT JOIN moderator_settings os ON os.user_id = o.moderator_id
                            WHERE o.community_id = s.community_id AND o.period = s.period AND o.activity >= :min
                              AND coalesce(os.public_ranking, TRUE) AND ou.deleted_at IS NULL
                              AND (o.score > s.score OR (o.score = s.score AND o.moderator_id < s.moderator_id))) END AS rank
                        FROM moderator_scores s
                        JOIN communities c ON c.id = s.community_id
                        LEFT JOIN moderator_settings ms ON ms.user_id = s.moderator_id
                        WHERE s.moderator_id = :u AND (:self OR c.visibility <> 'PRIVATE')
                        ORDER BY s.period DESC, s.score DESC LIMIT 24""")
                .param("min", ModeratorScoreMath.MIN_ACTIVITY).param("u", user.getId()).param("self", self)
                .query((rs, i) -> {
                    int r = rs.getInt("rank");
                    return rows.add(row(rs, rs.wasNull() ? null : r));
                }).list();
        return new ModerationRecord(false, self && optedOut, toDtos(rows), Formula.current());
    }

    // ------------------------------------------------------------------ peers and endorsements

    private Community rosterModerator(String slug, AuthUser viewer) {
        if (viewer == null) throw ApiException.unauthorized("Authentication required");
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        boolean mod = memberships.role(c.getId(), viewer.id()).map(CommunityRole::isModerator).orElse(false);
        if (!mod) throw ApiException.forbidden("Only this community's moderators can endorse each other");
        return c;
    }

    @Transactional(readOnly = true)
    public Peers peers(String slug, AuthUser viewer) {
        Community c = rosterModerator(slug, viewer);
        String p = currentPeriod().toString();
        List<Long> mods = memberships.moderators(c.getId()).stream().map(MembershipDao.MemberRow::userId)
                .filter(id -> id != viewer.id()).toList();
        Map<Long, String> mine = new HashMap<>();
        Set<Long> endorsedByMe = new HashSet<>();
        jdbc.sql("SELECT moderator_id, note FROM moderator_endorsements WHERE community_id = ? AND period = ? AND endorser_id = ?")
                .params(c.getId(), p, viewer.id())
                .query((rs, i) -> {
                    endorsedByMe.add(rs.getLong(1));
                    return mine.put(rs.getLong(1), rs.getString(2));
                }).list();
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.sql("SELECT moderator_id, count(*) FROM moderator_endorsements WHERE community_id = ? AND period = ? GROUP BY 1")
                .params(c.getId(), p).query((rs, i) -> counts.put(rs.getLong(1), rs.getInt(2))).list();
        Map<Long, UserSummary> u = directory.summaries(mods);
        return new Peers(p, CommunityMapper.ref(c), mods.stream().filter(u::containsKey)
                .map(id -> new Peer(u.get(id), endorsedByMe.contains(id), mine.get(id), counts.getOrDefault(id, 0))).toList());
    }

    @Transactional
    public void endorse(String slug, AuthUser viewer, String username, String note) {
        Community c = rosterModerator(slug, viewer);
        limiter.check("endorse:" + viewer.id(), 30, Duration.ofHours(1));
        User target = users.findByUsernameIgnoreCase(username).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("User"));
        if (target.getId() == viewer.id()) throw ApiException.badRequest("You can't endorse yourself");
        boolean targetMod = memberships.role(c.getId(), target.getId()).map(CommunityRole::isModerator).orElse(false);
        if (!targetMod) throw ApiException.badRequest("You can only endorse a moderator of this community");
        YearMonth period = currentPeriod();
        String clean = note == null || note.isBlank() ? null : note.strip();
        jdbc.sql("""
                        INSERT INTO moderator_endorsements (community_id, period, endorser_id, moderator_id, note) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (community_id, period, endorser_id, moderator_id) DO UPDATE SET note = EXCLUDED.note""")
                .params(c.getId(), period.toString(), viewer.id(), target.getId(), clean).update();
        recompute(period, c.getId());
    }

    @Transactional
    public void withdraw(String slug, AuthUser viewer, String username) {
        Community c = rosterModerator(slug, viewer);
        User target = users.findByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("User"));
        YearMonth period = currentPeriod();
        int n = jdbc.sql("DELETE FROM moderator_endorsements WHERE community_id = ? AND period = ? AND endorser_id = ? AND moderator_id = ?")
                .params(c.getId(), period.toString(), viewer.id(), target.getId()).update();
        if (n == 0) throw ApiException.notFound("Endorsement");
        recompute(period, c.getId());
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public ModeratorSettings settings(AuthUser viewer) {
        if (viewer == null) throw ApiException.unauthorized("Authentication required");
        return new ModeratorSettings(publicRanking(viewer.id()));
    }

    @Transactional
    public ModeratorSettings updateSettings(AuthUser viewer, boolean publicRanking) {
        if (viewer == null) throw ApiException.unauthorized("Authentication required");
        jdbc.sql("""
                        INSERT INTO moderator_settings (user_id, public_ranking) VALUES (?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET public_ranking = EXCLUDED.public_ranking, updated_at = now()""")
                .params(viewer.id(), publicRanking).update();
        return new ModeratorSettings(publicRanking);
    }
}

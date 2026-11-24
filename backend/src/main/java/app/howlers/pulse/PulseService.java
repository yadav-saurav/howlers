package app.howlers.pulse;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.community.Community;
import app.howlers.community.CommunityAccess;
import app.howlers.community.CommunityMapper;
import app.howlers.community.CommunityRepository;
import app.howlers.community.CommunityRole;
import app.howlers.notification.NotificationService;
import app.howlers.pulse.PulseDtos.CreatePulse;
import app.howlers.pulse.PulseDtos.OptionDto;
import app.howlers.pulse.PulseDtos.PulseDto;
import app.howlers.pulse.PulseDtos.PulseReason;
import app.howlers.pulse.PulseDtos.PulseViewer;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Community Pulse: time-boxed polls, predictions, opinion questions and debate prompts created by moderators.
 *
 * <ul>
 *   <li>Status is derived on read (OPEN until closes_at or an early close; RESOLVED once a prediction's outcome is set),
 *       so no scheduler is needed to close pulses.</li>
 *   <li>Results stay hidden until you vote or the pulse closes, so early tallies don't anchor later voters.</li>
 *   <li>Votes are serialised per (pulse, voter) with an advisory lock; counters move by relative deltas.</li>
 *   <li>No money or stakes: predictions only record accuracy.</li>
 * </ul>
 */
@Service
public class PulseService {
    private static final int PULSE_NS = 0x50554C53; // "PULS"

    private final JdbcClient jdbc;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final UserDirectory users;
    private final NotificationService notifications;
    private final RateLimiter limiter;
    private final app.howlers.realtime.RealtimePublisher realtime;

    public PulseService(JdbcClient jdbc, CommunityRepository communities, CommunityAccess access, UserDirectory users,
                        NotificationService notifications, RateLimiter limiter,
                        app.howlers.realtime.RealtimePublisher realtime) {
        this.realtime = realtime;
        this.jdbc = jdbc;
        this.communities = communities;
        this.access = access;
        this.users = users;
        this.notifications = notifications;
        this.limiter = limiter;
    }

    private record Row(long id, long communityId, Long authorId, String type, String title, String description, boolean multiple,
                       boolean anonymous, boolean allowChange, Instant closesAt, Instant closedEarly, Long resolvedOption,
                       int voters, Instant createdAt) {
        boolean open(Instant now) {
            return closedEarly == null && closesAt.isAfter(now);
        }

        String status(Instant now) {
            if (resolvedOption != null) return "RESOLVED";
            return open(now) ? "OPEN" : "CLOSED";
        }
    }

    private static final String COLS = """
            id, community_id, author_id, type, title, description, multiple, anonymous, allow_change, closes_at, closed_early_at,
            resolved_option_id, total_voters, created_at""";

    private Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9), ts(rs.getObject(10, OffsetDateTime.class)),
                ts(rs.getObject(11, OffsetDateTime.class)), (Long) rs.getObject(12), rs.getInt(13), ts(rs.getObject(14, OffsetDateTime.class)));
    }

    // ------------------------------------------------------------------ create & manage

    @Transactional
    public PulseDto create(String slug, AuthUser user, CreatePulse req) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        limiter.check("pulse:create:" + user.id(), 20, Duration.ofDays(1));
        List<String> labels = req.options().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (labels.size() < 2) throw ApiException.field("options", "Add at least two options");
        if (new HashSet<>(labels.stream().map(String::toLowerCase).toList()).size() != labels.size()) {
            throw ApiException.field("options", "Options must be different");
        }
        if ("PREDICTION".equals(req.type()) && req.multiple()) throw ApiException.field("multiple", "Predictions take a single answer");
        Instant closes = Instant.now().plus(Duration.ofHours(req.durationHours()));
        long id = jdbc.sql("""
                        INSERT INTO pulses (community_id, author_id, type, title, description, multiple, anonymous, allow_change, closes_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(c.getId(), user.id(), req.type(), req.title().strip(), req.description() == null ? "" : req.description().strip(),
                        req.multiple(), req.anonymous(), req.allowChange(), Timestamp.from(closes))
                .query(Long.class).single();
        for (int i = 0; i < labels.size(); i++) {
            jdbc.sql("INSERT INTO pulse_options (pulse_id, position, label) VALUES (?, ?, ?)").params(id, i, labels.get(i)).update();
        }
        return get(id, user);
    }

    @Transactional
    public PulseDto close(long id, AuthUser user) {
        Row r = manageable(id, user);
        if (!r.open(Instant.now())) throw ApiException.badRequest("This pulse is already closed");
        jdbc.sql("UPDATE pulses SET closed_early_at = now() WHERE id = ?").param(id).update();
        return get(id, user);
    }

    /** Record a prediction's real outcome (after it closed) and tell every voter how they did. */
    @Transactional
    public PulseDto resolve(long id, AuthUser user, long optionId) {
        Row r = manageable(id, user);
        if (!"PREDICTION".equals(r.type())) throw ApiException.badRequest("Only predictions can be resolved");
        if (r.open(Instant.now())) throw ApiException.badRequest("Close the prediction before resolving it");
        if (r.resolvedOption() != null) throw ApiException.badRequest("This prediction is already resolved");
        boolean belongs = jdbc.sql("SELECT EXISTS (SELECT 1 FROM pulse_options WHERE id = ? AND pulse_id = ?)").params(optionId, id)
                .query(Boolean.class).single();
        if (!belongs) throw ApiException.field("optionId", "Unknown option");
        jdbc.sql("UPDATE pulses SET resolved_option_id = ? WHERE id = ?").params(optionId, id).update();
        Community c = communities.findById(r.communityId()).orElseThrow();
        record V(long userId, boolean correct) {}
        jdbc.sql("SELECT user_id, bool_or(option_id = ?) FROM pulse_votes WHERE pulse_id = ? GROUP BY user_id").params(optionId, id)
                .query((rs, i) -> new V(rs.getLong(1), rs.getBoolean(2))).list()
                .forEach(v -> notifications.pulseResult(v.userId(), c.getSlug(), id, r.title(), v.correct()));
        return get(id, user);
    }

    @Transactional
    public void delete(long id, AuthUser user) {
        manageable(id, user);
        jdbc.sql("DELETE FROM pulses WHERE id = ?").param(id).update();
    }

    private Row manageable(long id, AuthUser user) {
        Row r = load(id);
        Community c = communities.findById(r.communityId()).orElseThrow(() -> ApiException.notFound("Pulse"));
        access.requireModerator(c, user);
        return r;
    }

    // ------------------------------------------------------------------ vote

    @Transactional
    public PulseDto vote(long id, AuthUser user, List<Long> optionIds, String reasoning) {
        limiter.check("pulse:vote:" + user.id(), 120, Duration.ofMinutes(10));
        Row r = load(id);
        Community c = viewable(r, user);
        access.requireNotBanned(c.getId(), user);
        if (c.visibility() == Community.Visibility.PRIVATE && access.roleOf(c, user).isEmpty()) {
            throw ApiException.forbidden("Join this community to take part");
        }
        if (!r.open(Instant.now())) throw ApiException.badRequest("This pulse has closed");
        Set<Long> chosen = new LinkedHashSet<>(optionIds);
        if (!r.multiple() && chosen.size() > 1) throw ApiException.badRequest("Choose one option");
        Set<Long> valid = new HashSet<>(jdbc.sql("SELECT id FROM pulse_options WHERE pulse_id = ?").param(id).query(Long.class).list());
        if (!valid.containsAll(chosen)) throw ApiException.badRequest("Unknown option");

        jdbc.sql("SELECT pg_advisory_xact_lock(?, ?)").params(PULSE_NS ^ Long.hashCode(id), Long.hashCode(user.id()))
                .query(Object.class).list();
        List<Long> previous = jdbc.sql("SELECT option_id FROM pulse_votes WHERE pulse_id = ? AND user_id = ?").params(id, user.id())
                .query(Long.class).list();
        if (!previous.isEmpty() && !r.allowChange()) throw ApiException.conflict(null, "Votes in this pulse can't be changed");
        for (Long o : previous) jdbc.sql("UPDATE pulse_options SET votes = GREATEST(votes - 1, 0) WHERE id = ?").param(o).update();
        jdbc.sql("DELETE FROM pulse_votes WHERE pulse_id = ? AND user_id = ?").params(id, user.id()).update();
        for (Long o : chosen) {
            jdbc.sql("INSERT INTO pulse_votes (pulse_id, option_id, user_id) VALUES (?, ?, ?)").params(id, o, user.id()).update();
            jdbc.sql("UPDATE pulse_options SET votes = votes + 1 WHERE id = ?").param(o).update();
        }
        if (previous.isEmpty()) jdbc.sql("UPDATE pulses SET total_voters = total_voters + 1 WHERE id = ?").param(id).update();
        if (reasoning != null && !reasoning.isBlank()) {
            jdbc.sql("""
                            INSERT INTO pulse_reasons (pulse_id, user_id, text) VALUES (?, ?, ?)
                            ON CONFLICT (pulse_id, user_id) DO UPDATE SET text = EXCLUDED.text, created_at = now()""")
                    .params(id, user.id(), reasoning.strip()).update();
        }
        int total = jdbc.sql("SELECT total_voters FROM pulses WHERE id = ?").param(id).query(Integer.class).single();
        realtime.pulseChanged(id, total);
        return get(id, user);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PulseDto get(long id, AuthUser user) {
        Row r = load(id);
        Community c = viewable(r, user);
        return toDtos(List.of(r), Map.of(c.getId(), c), user).getFirst();
    }

    @Transactional(readOnly = true)
    public List<PulseDto> active(String slug, AuthUser user) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireView(c, user);
        List<Row> rows = jdbc.sql("SELECT " + COLS + " FROM pulses WHERE community_id = ? AND closed_early_at IS NULL AND closes_at > now() ORDER BY closes_at")
                .param(c.getId()).query(this::row).list();
        return toDtos(rows, Map.of(c.getId(), c), user);
    }

    @Transactional(readOnly = true)
    public CursorPage<PulseDto> history(String slug, AuthUser user, String cursor, int limit) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireView(c, user);
        Cursors.Key key = Cursors.decode(cursor);
        List<Row> rows = jdbc.sql("SELECT " + COLS + """

                        FROM pulses WHERE community_id = :c AND (closed_early_at IS NOT NULL OR closes_at <= now())
                          AND (:hasCursor = FALSE OR id < :cid) ORDER BY id DESC LIMIT :lim""")
                .param("c", c.getId()).param("hasCursor", key != null).param("cid", key == null ? 0L : key.id()).param("lim", limit + 1)
                .query(this::row).list();
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        return new CursorPage<>(toDtos(page, Map.of(c.getId(), c), user), more ? Cursors.encode(0, page.getLast().id()) : null);
    }

    /** Open pulses in the viewer's communities (anonymous: from the most popular public communities). */
    @Transactional(readOnly = true)
    public List<PulseDto> activeForViewer(AuthUser user, int limit) {
        List<Row> rows = jdbc.sql("SELECT " + COLS + """

                        FROM pulses p WHERE p.closed_early_at IS NULL AND p.closes_at > now()
                          AND (CAST(:viewer AS bigint) IS NULL
                               AND EXISTS (SELECT 1 FROM communities c WHERE c.id = p.community_id AND c.visibility <> 'PRIVATE')
                            OR EXISTS (SELECT 1 FROM community_members m WHERE m.community_id = p.community_id AND m.user_id = CAST(:viewer AS bigint)))
                        ORDER BY p.total_voters DESC, p.closes_at LIMIT :lim""")
                .param("viewer", user == null ? null : user.id()).param("lim", Math.min(Math.max(limit, 1), 10))
                .query(this::row).list();
        Map<Long, Community> cs = new HashMap<>();
        communities.findAllById(rows.stream().map(Row::communityId).distinct().toList()).forEach(c -> cs.put(c.getId(), c));
        return toDtos(rows, cs, user);
    }

    @Transactional(readOnly = true)
    public List<PulseReason> reasons(long id, AuthUser user) {
        PulseDto p = get(id, user);
        if (!p.resultsVisible()) return List.of();
        record R(long userId, String text, Instant at) {}
        List<R> rows = jdbc.sql("SELECT user_id, text, created_at FROM pulse_reasons WHERE pulse_id = ? ORDER BY created_at DESC LIMIT 50")
                .param(id).query((rs, i) -> new R(rs.getLong(1), rs.getString(2), ts(rs.getObject(3, OffsetDateTime.class)))).list();
        Map<Long, Long> optionOf = new HashMap<>();
        jdbc.sql("SELECT user_id, min(option_id) FROM pulse_votes WHERE pulse_id = ? GROUP BY user_id").param(id)
                .query((rs, i) -> optionOf.put(rs.getLong(1), rs.getLong(2))).list();
        Map<Long, UserSummary> authors = p.anonymous() ? Map.of() : users.summaries(rows.stream().map(R::userId).toList());
        return rows.stream().filter(r -> optionOf.containsKey(r.userId()))
                .map(r -> new PulseReason(p.anonymous() ? null : authors.get(r.userId()), optionOf.get(r.userId()), r.text(), r.at())).toList();
    }

    private List<PulseDto> toDtos(List<Row> rows, Map<Long, Community> cs, AuthUser user) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(Row::id).toList();
        record O(long id, long pulseId, String label, int votes) {}
        Map<Long, List<O>> options = new HashMap<>();
        jdbc.sql("SELECT id, pulse_id, label, votes FROM pulse_options WHERE pulse_id IN (:ids) ORDER BY pulse_id, position")
                .param("ids", ids)
                .query((rs, i) -> new O(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4))).list()
                .forEach(o -> options.computeIfAbsent(o.pulseId(), k -> new ArrayList<>()).add(o));
        Map<Long, List<Long>> mine = new HashMap<>();
        if (user != null) {
            jdbc.sql("SELECT pulse_id, option_id FROM pulse_votes WHERE user_id = :u AND pulse_id IN (:ids)")
                    .param("u", user.id()).param("ids", ids)
                    .query((rs, i) -> mine.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2))).list();
        }
        Map<Long, Double> accuracy = new HashMap<>();
        List<Long> resolved = rows.stream().filter(r -> r.resolvedOption() != null).map(Row::id).toList();
        if (!resolved.isEmpty()) {
            jdbc.sql("""
                            SELECT v.pulse_id, avg(CASE WHEN v.option_id = p.resolved_option_id THEN 1.0 ELSE 0.0 END)
                            FROM pulse_votes v JOIN pulses p ON p.id = v.pulse_id WHERE v.pulse_id IN (:ids) GROUP BY v.pulse_id""")
                    .param("ids", resolved)
                    .query((rs, i) -> accuracy.put(rs.getLong(1), rs.getDouble(2))).list();
        }
        Map<Long, UserSummary> authors = users.summaries(rows.stream().map(Row::authorId).toList());
        Instant now = Instant.now();
        List<PulseDto> out = new ArrayList<>();
        for (Row r : rows) {
            Community c = cs.get(r.communityId());
            if (c == null) continue;
            List<Long> my = mine.getOrDefault(r.id(), List.of());
            boolean open = r.open(now);
            boolean visible = !open || !my.isEmpty();
            Optional<CommunityRole> role = access.roleOf(c, user);
            boolean canManage = access.canModerate(role, user);
            boolean canVote = user != null && open && (my.isEmpty() || r.allowChange())
                    && access.banOf(c.getId(), user).isEmpty()
                    && (c.visibility() != Community.Visibility.PRIVATE || role.isPresent());
            List<OptionDto> opts = options.getOrDefault(r.id(), List.of()).stream()
                    .map(o -> new OptionDto(o.id(), o.label(), visible ? o.votes() : null,
                            r.resolvedOption() != null && r.resolvedOption() == o.id()))
                    .toList();
            out.add(new PulseDto(r.id(), CommunityMapper.ref(c), r.type(), r.title(), r.description(), r.multiple(), r.anonymous(),
                    r.allowChange(), r.closedEarly() != null ? r.closedEarly() : r.closesAt(), r.status(now), r.createdAt(),
                    r.authorId() == null ? null : authors.get(r.authorId()), r.voters(), my, visible, opts, r.resolvedOption(),
                    accuracy.get(r.id()), new PulseViewer(canVote, canManage)));
        }
        return out;
    }

    private Row load(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM pulses WHERE id = ?").param(id).query(this::row).optional()
                .orElseThrow(() -> ApiException.notFound("Pulse"));
    }

    private Community viewable(Row r, AuthUser user) {
        Community c = communities.findById(r.communityId()).orElseThrow(() -> ApiException.notFound("Pulse"));
        access.requireView(c, user);
        return c;
    }

    private static Instant ts(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}

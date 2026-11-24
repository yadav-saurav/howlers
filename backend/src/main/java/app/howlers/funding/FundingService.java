package app.howlers.funding;

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
import app.howlers.funding.FundingDtos.Allocation;
import app.howlers.funding.FundingDtos.AllocationPreview;
import app.howlers.funding.FundingDtos.CloseRequest;
import app.howlers.funding.FundingDtos.CommunityFunding;
import app.howlers.funding.FundingDtos.CommunityViewer;
import app.howlers.funding.FundingDtos.ContributeRequest;
import app.howlers.funding.FundingDtos.ContributeResult;
import app.howlers.funding.FundingDtos.Contribution;
import app.howlers.funding.FundingDtos.CreatePoolRequest;
import app.howlers.funding.FundingDtos.CurrencyInfo;
import app.howlers.funding.FundingDtos.FundingStatus;
import app.howlers.funding.FundingDtos.LedgerEntry;
import app.howlers.funding.FundingDtos.ManualAllocation;
import app.howlers.funding.FundingDtos.MyFunding;
import app.howlers.funding.FundingDtos.Policy;
import app.howlers.funding.FundingDtos.PolicyRequest;
import app.howlers.funding.FundingDtos.PoolRef;
import app.howlers.funding.FundingDtos.PoolSummary;
import app.howlers.funding.FundingDtos.Reconciliation;
import app.howlers.funding.FundingDtos.SubscriptionDto;
import app.howlers.funding.FundingDtos.TransparencyReport;
import app.howlers.funding.FundingDtos.WebhookResult;
import app.howlers.funding.payment.DemoPaymentProvider;
import app.howlers.funding.payment.FundingRuntime;
import app.howlers.funding.payment.PaymentProvider;
import app.howlers.funding.payment.PaymentProvider.Charge;
import app.howlers.funding.payment.PaymentProvider.ChargeRequest;
import app.howlers.funding.payment.PaymentProvider.Subscription;
import app.howlers.funding.payment.PaymentProvider.SubscriptionRequest;
import app.howlers.funding.payment.PaymentProvider.WebhookEvent;
import app.howlers.funding.payment.PaymentProvider.WebhookVerificationException;
import app.howlers.user.User;
import app.howlers.user.UserDirectory;
import app.howlers.user.UserRepository;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moderator funding: pools, contributions (one-time and monthly), allocation policies, period close and the
 * transparency report. Money is integer minor units everywhere. Contributions, allocations and ledger rows are
 * append-only (enforced by database triggers as well as by this class never updating them).
 */
@Service
public class FundingService {
    private static final Logger log = LoggerFactory.getLogger(FundingService.class);
    static final String CONTRIBUTORS_ACCOUNT = "contributors";
    private static final int MAX_OPEN_POOLS = 3;
    private static final int LEDGER_LIMIT = 1000;
    private static final Duration MAX_PERIOD = Duration.ofDays(366);
    private static final Duration MIN_PERIOD = Duration.ofDays(1);
    /** Allocations are obligations recorded in the ledger; no provider payout (e.g. Stripe Connect) exists yet (D-034). */
    static final String PAYOUTS = "Allocated, not paid out: allocations are recorded obligations and no money is sent to moderators.";

    private final JdbcClient jdbc;
    private final FundingRuntime runtime;
    private final CommunityRepository communities;
    private final CommunityAccess access;
    private final MembershipDao memberships;
    private final UserRepository users;
    private final UserDirectory directory;
    private final RateLimiter limiter;
    private final PayoutService payouts;

    public FundingService(JdbcClient jdbc, FundingRuntime runtime, CommunityRepository communities, CommunityAccess access,
                          MembershipDao memberships, UserRepository users, UserDirectory directory, RateLimiter limiter, PayoutService payouts) {
        this.payouts = payouts;
        this.jdbc = jdbc;
        this.runtime = runtime;
        this.communities = communities;
        this.access = access;
        this.memberships = memberships;
        this.users = users;
        this.directory = directory;
        this.limiter = limiter;
    }

    // ------------------------------------------------------------------ status

    public FundingStatus status() {
        List<CurrencyInfo> currencies = Money.CURRENCY_ORDER.stream().map(c -> new CurrencyInfo(c, Money.CURRENCIES.get(c))).toList();
        if (!runtime.enabled()) {
            return new FundingStatus(false, null, true, Money.MODE_DEMO, Money.DEMO_LABEL, runtime.disabledReason(), null, PAYOUTS, currencies);
        }
        PaymentProvider p = runtime.provider();
        return new FundingStatus(true, p.id(), p.demo(), p.mode(), p.label(), p.notice(), p.hostedCheckout() ? "hosted" : "instant",
                payoutsNotice(p), currencies);
    }

    /** What happens to allocations: recorded only (demo, or Stripe without Connect), or paid out through Stripe Connect (D-046). */
    private String payoutsNotice(PaymentProvider p) {
        if (!payouts.enabled() || !PayoutService.paysOut(p.id(), p.mode())) {
            return PayoutService.paysOut(p.id(), p.mode())
                    ? PAYOUTS + " Each Stripe pool's shares are held until payouts are enabled on this server."
                    : PAYOUTS;
        }
        return Money.MODE_TEST.equals(p.mode())
                ? "Paid out through Stripe Connect in test mode (no real money): when a pool closes, each moderator's share is transferred to "
                + "their connected Stripe account. A moderator without one has their share held until they connect it."
                : "Paid out through Stripe Connect: when a pool closes, each moderator's share is transferred to their connected Stripe account. "
                + "A moderator without one has their share held until they connect it.";
    }

    // ------------------------------------------------------------------ community overview, policy

    @Transactional(readOnly = true)
    public CommunityFunding community(String slug, AuthUser user) {
        Community c = viewable(slug, user);
        Optional<CommunityRole> role = access.roleOf(c, user);
        boolean enabled = runtime.enabled();
        boolean banned = user != null && access.banOf(c.getId(), user).isPresent();
        boolean owner = isOwner(role, user);
        CommunityViewer viewer = new CommunityViewer(enabled && role.isPresent() && !banned,
                enabled && access.canModerate(role, user), enabled && owner, enabled && owner);
        List<PoolRow> rows = jdbc.sql(POOL_SQL + " WHERE p.community_id = ? ORDER BY (p.status = 'OPEN') DESC, p.id DESC LIMIT 50")
                .param(c.getId()).query(this::poolRow).list();
        return new CommunityFunding(status(), policy(c.getId()), summaries(rows), viewer);
    }

    @Transactional
    public Policy setPolicy(String slug, AuthUser user, PolicyRequest req) {
        runtime.require();
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        requireOwner(c, user);
        int cap = req.activityCap() == null ? 50 : req.activityCap();
        jdbc.sql("""
                        INSERT INTO funding_policies (community_id, policy, activity_cap, updated_by, updated_at) VALUES (?, ?, ?, ?, now())
                        ON CONFLICT (community_id) DO UPDATE SET policy = EXCLUDED.policy, activity_cap = EXCLUDED.activity_cap,
                          updated_by = EXCLUDED.updated_by, updated_at = now()""")
                .params(c.getId(), req.policy(), cap, user.id()).update();
        return policy(c.getId());
    }

    private Policy policy(long communityId) {
        record P(String policy, int cap, Long by, Instant at) {}
        return jdbc.sql("SELECT policy, activity_cap, updated_by, updated_at FROM funding_policies WHERE community_id = ?")
                .param(communityId).query((rs, i) -> new P(rs.getString(1), rs.getInt(2), (Long) rs.getObject(3), ts(rs, 4)))
                .optional()
                .map(p -> new Policy(p.policy(), p.cap(), p.by() == null ? null : directory.summaries(List.of(p.by())).get(p.by()), p.at()))
                .orElse(new Policy("EQUAL", 50, null, null));
    }

    // ------------------------------------------------------------------ pools

    @Transactional
    public PoolSummary createPool(String slug, AuthUser user, CreatePoolRequest req) {
        PaymentProvider provider = runtime.require();
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireModerator(c, user);
        limiter.check("funding-pool:" + user.id(), 10, Duration.ofHours(1));
        if (!Money.supported(req.currency())) throw ApiException.field("currency", "Choose one of " + String.join(", ", Money.CURRENCY_ORDER));
        if (req.goalMinor() > Money.maxGoal(req.currency())) throw ApiException.field("goalMinor", "That goal is too large");
        if (req.goalMinor() < Money.unit(req.currency())) throw ApiException.field("goalMinor", "The goal must be at least 1 " + req.currency());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant start = req.periodStart() == null || req.periodStart().isBefore(now) ? now : req.periodStart().truncatedTo(ChronoUnit.MICROS);
        Instant end = req.periodEnd().truncatedTo(ChronoUnit.MICROS);
        Duration len = Duration.between(start, end);
        if (len.compareTo(MIN_PERIOD) < 0) throw ApiException.field("periodEnd", "The period must last at least a day");
        if (len.compareTo(MAX_PERIOD) > 0) throw ApiException.field("periodEnd", "The period can last at most a year");
        int open = jdbc.sql("SELECT count(*) FROM funding_pools WHERE community_id = ? AND status = 'OPEN'").param(c.getId())
                .query(Integer.class).single();
        if (open >= MAX_OPEN_POOLS) throw ApiException.badRequest("A community can run at most " + MAX_OPEN_POOLS + " open pools");
        long id = jdbc.sql("""
                        INSERT INTO funding_pools (community_id, created_by, title, purpose, currency, goal_minor, period_start, period_end,
                          provider, demo, mode)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(c.getId(), user.id(), req.title().strip(), req.purpose().strip(), req.currency(), req.goalMinor(),
                        Timestamp.from(start), Timestamp.from(end), provider.id(), provider.demo(), provider.mode())
                .query(Long.class).single();
        return summaries(List.of(pool(id))).getFirst();
    }

    // ------------------------------------------------------------------ contributions

    /** Instant (demo) contributions. Hosted-checkout providers go through {@link FundingCheckouts#contribute}. */
    @Transactional
    public ContributeResult contribute(long poolId, AuthUser user, ContributeRequest req) {
        PaymentProvider provider = runtime.require();
        if (provider.hostedCheckout()) throw new IllegalStateException("Hosted-checkout contributions go through FundingCheckouts");
        PoolRow p = checkContribution(lockPool(poolId), provider, user, req);
        long amount = req.amountMinor();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!req.recurring()) {
            Charge charge = provider.createCharge(new ChargeRequest(amount, p.currency(), "Pool " + p.id(),
                    "one:" + user.id() + ":" + UUID.randomUUID()));
            if (!charge.succeeded()) throw ApiException.badRequest("The payment didn't go through");
            long cid = recordContribution(p, user.id(), null, null, "ONE_TIME", amount, req.showName(), provider, charge.chargeId(), null);
            return new ContributeResult(contribution(cid), null, null);
        }
        Subscription sub = provider.createSubscription(new SubscriptionRequest(amount, p.currency(), "Pool " + p.id() + " monthly",
                "sub:" + user.id() + ":" + UUID.randomUUID()));
        long subId = jdbc.sql("""
                        INSERT INTO funding_subscriptions (pool_id, contributor_id, amount_minor, currency, show_name, provider, provider_ref,
                          demo, mode, next_charge_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(p.id(), user.id(), amount, p.currency(), req.showName(), provider.id(), sub.subscriptionRef(), provider.demo(),
                        provider.mode(), Timestamp.from(now))
                .query(Long.class).single();
        Long cid = chargeCycleLocked(subId, now); // the first month is charged right away
        return new ContributeResult(cid == null ? null : contribution(cid), subscription(subId), null);
    }

    /**
     * Who may contribute what, shared by the instant and hosted paths: a member who isn't banned, an open pool made with
     * the active provider and mode, and an amount within limits.
     */
    PoolRow checkContribution(PoolRow p, PaymentProvider provider, AuthUser user, ContributeRequest req) {
        Community c = communities.findById(p.communityId()).orElseThrow(() -> ApiException.notFound("Pool"));
        Optional<CommunityRole> role = access.roleOf(c, user);
        if (!access.canView(c, role, user)) throw ApiException.notFound("Pool");
        if (role.isEmpty()) throw ApiException.forbidden("Join c/" + c.getSlug() + " to contribute");
        access.requireNotBanned(c.getId(), user);
        limiter.check("funding-contribute:" + user.id(), 20, Duration.ofHours(1));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!"OPEN".equals(p.status()) || now.isBefore(p.periodStart()) || !now.isBefore(p.periodEnd())) {
            throw ApiException.badRequest("This pool isn't accepting contributions");
        }
        if (!p.provider().equals(provider.id()) || !p.mode().equals(provider.mode())) {
            throw ApiException.badRequest("This pool was created with a different payment provider or mode, so it can't take contributions now");
        }
        long amount = req.amountMinor();
        if (amount < Money.minContribution(p.currency()) || amount > Money.maxContribution(p.currency())) {
            throw ApiException.field("amountMinor", "Contributions must be between " + Money.minContribution(p.currency()) + " and "
                    + Money.maxContribution(p.currency()) + " minor units of " + p.currency());
        }
        return p;
    }

    /**
     * Records a confirmed contribution and its ledger row (contributors to pool), in the pool's provider and mode.
     * {@code chargeId} is unique, so a second attempt to record the same payment fails with a duplicate key.
     */
    long recordContribution(PoolRow p, long userId, Long subId, Integer cycle, String kind, long amount, boolean showName,
                            PaymentProvider provider, String chargeId, String paymentRef) {
        long cid = jdbc.sql("""
                        INSERT INTO funding_contributions (pool_id, contributor_id, subscription_id, cycle, kind, amount_minor, currency,
                          show_name, provider, provider_charge_id, provider_payment_ref, demo, mode)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(p.id(), userId, subId, cycle, kind, amount, p.currency(), showName, provider.id(), chargeId, paymentRef, p.demo(), p.mode())
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO funding_ledger (pool_id, entry_type, debit_account, credit_account, amount_minor, currency, contribution_id, demo, mode)
                        VALUES (?, 'CONTRIBUTION', ?, ?, ?, ?, ?, ?, ?)""")
                .params(p.id(), CONTRIBUTORS_ACCOUNT, poolAccount(p.id()), amount, p.currency(), cid, p.demo(), p.mode()).update();
        return cid;
    }

    /**
     * Reverses (part of) a contribution with new rows: a refund and a REFUND ledger row moving the amount from the pool
     * back to contributors. Nothing is edited. {@code refundRef} is unique, so the same refund state is recorded once, and
     * a contribution is never reversed by more than its amount. Returns the refund id, or null when there was nothing to record.
     */
    Long recordRefund(long contributionId, long amount, String refundRef) {
        record C(long poolId, long amount, String currency, String provider, boolean demo, String mode) {}
        C c = jdbc.sql("SELECT pool_id, amount_minor, currency, provider, demo, mode FROM funding_contributions WHERE id = ?")
                .param(contributionId)
                .query((rs, i) -> new C(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6)))
                .single();
        lockPool(c.poolId());
        if (jdbc.sql("SELECT count(*) FROM funding_refunds WHERE provider_refund_ref = ?").param(refundRef).query(Long.class).single() > 0) return null;
        long already = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_refunds WHERE contribution_id = ?").param(contributionId)
                .query(Long.class).single();
        long delta = Math.min(amount, c.amount() - already);
        if (delta <= 0) return null;
        long rid = jdbc.sql("""
                        INSERT INTO funding_refunds (pool_id, contribution_id, amount_minor, currency, provider, provider_refund_ref, demo, mode)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(c.poolId(), contributionId, delta, c.currency(), c.provider(), refundRef, c.demo(), c.mode())
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO funding_ledger (pool_id, entry_type, debit_account, credit_account, amount_minor, currency, refund_id, demo, mode)
                        VALUES (?, 'REFUND', ?, ?, ?, ?, ?, ?, ?)""")
                .params(c.poolId(), poolAccount(c.poolId()), CONTRIBUTORS_ACCOUNT, delta, c.currency(), rid, c.demo(), c.mode()).update();
        return rid;
    }

    /** Total refunded so far for a contribution. */
    long refundedOf(long contributionId) {
        return jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_refunds WHERE contribution_id = ?").param(contributionId)
                .query(Long.class).single();
    }

    /**
     * Charges the next monthly cycle of a subscription if it's due. Safe to call concurrently and repeatedly: the row is
     * locked, and (subscription_id, cycle) is unique, so a cycle is never charged twice. Returns the new contribution id.
     */
    @Transactional
    public Long chargeCycle(long subscriptionId, Instant now) {
        return chargeCycleLocked(subscriptionId, now);
    }

    private Long chargeCycleLocked(long subscriptionId, Instant now) {
        record S(long id, long poolId, long userId, long amount, boolean showName, String status, String provider, int cycles,
                 Instant next) {}
        S s = jdbc.sql("""
                        SELECT id, pool_id, contributor_id, amount_minor, show_name, status, provider, cycles_charged, next_charge_at
                        FROM funding_subscriptions WHERE id = ? FOR UPDATE""")
                .param(subscriptionId)
                .query((rs, i) -> new S(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5), rs.getString(6),
                        rs.getString(7), rs.getInt(8), ts(rs, 9)))
                .optional().orElse(null);
        if (s == null || !"ACTIVE".equals(s.status()) || s.next() == null || s.next().isAfter(now)) return null;
        PoolRow p = pool(s.poolId());
        if (!"OPEN".equals(p.status()) || !now.isBefore(p.periodEnd())) {
            endSubscription(s.id());
            return null;
        }
        PaymentProvider provider = runtime.require();
        if (provider.hostedCheckout()) return null; // the provider (Stripe) bills cycles itself; Howlers records paid invoices
        if (!provider.id().equals(s.provider())) {
            log.warn("Subscription {} belongs to provider {} but {} is active; not charging", s.id(), s.provider(), provider.id());
            return null;
        }
        int cycle = s.cycles() + 1;
        Charge charge = provider.createCharge(new ChargeRequest(s.amount(), p.currency(), "Pool " + p.id() + " monthly",
                "sub:" + s.id() + ":cycle:" + cycle));
        if (!charge.succeeded()) return null;
        long cid;
        try {
            cid = recordContribution(p, s.userId(), s.id(), cycle, "RECURRING", s.amount(), s.showName(), provider, charge.chargeId(), null);
        } catch (DuplicateKeyException e) {
            return null; // another node charged this cycle first
        }
        // Next month from the scheduled date; if the job was down for longer than a month, skip ahead rather than back-charge.
        Instant next = s.next().atOffset(ZoneOffset.UTC).plusMonths(1).toInstant();
        if (!next.isAfter(now)) next = now.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant();
        if (!next.isBefore(p.periodEnd())) {
            jdbc.sql("UPDATE funding_subscriptions SET cycles_charged = ?, next_charge_at = NULL, status = 'ENDED', cancelled_by = 'SYSTEM', "
                    + "cancelled_at = now() WHERE id = ?").params(cycle, s.id()).update();
        } else {
            jdbc.sql("UPDATE funding_subscriptions SET cycles_charged = ?, next_charge_at = ? WHERE id = ?")
                    .params(cycle, Timestamp.from(next), s.id()).update();
        }
        return cid;
    }

    private void endSubscription(long id) {
        jdbc.sql("""
                        UPDATE funding_subscriptions SET status = 'ENDED', next_charge_at = NULL, cancelled_at = now(), cancelled_by = 'SYSTEM'
                        WHERE id = ? AND status = 'ACTIVE'""").param(id).update();
    }

    /** Subscriptions whose next charge is due. */
    @Transactional(readOnly = true)
    public List<Long> dueSubscriptions(Instant now, int limit) {
        return jdbc.sql("SELECT id FROM funding_subscriptions WHERE status = 'ACTIVE' AND next_charge_at <= ? ORDER BY next_charge_at LIMIT ?")
                .params(Timestamp.from(now), limit).query(Long.class).list();
    }

    @Transactional
    public SubscriptionDto cancelSubscription(long id, AuthUser user) {
        record S(Long userId, String status, String provider, String ref) {}
        S s = jdbc.sql("SELECT contributor_id, status, provider, provider_ref FROM funding_subscriptions WHERE id = ? FOR UPDATE").param(id)
                .query((rs, i) -> new S((Long) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4))).optional()
                .orElseThrow(() -> ApiException.notFound("Subscription"));
        if (!Objects.equals(s.userId(), user.id())) throw ApiException.notFound("Subscription");
        if (!"ACTIVE".equals(s.status())) return subscription(id); // already over: cancelling is idempotent
        PaymentProvider provider = runtime.require();
        if (provider.id().equals(s.provider())) {
            try {
                provider.cancel(s.ref());
            } catch (RuntimeException e) {
                log.warn("Cancelling subscription {} at {} failed: {}", id, provider.id(), e.getMessage());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR",
                        "The payment provider didn't confirm the cancellation. Nothing changed; please try again.");
            }
        }
        jdbc.sql("UPDATE funding_subscriptions SET status = 'CANCELLED', next_charge_at = NULL, cancelled_at = now(), cancelled_by = 'USER' WHERE id = ?")
                .param(id).update();
        return subscription(id);
    }

    @Transactional(readOnly = true)
    public MyFunding my(AuthUser user) {
        List<Long> cids = jdbc.sql("SELECT id FROM funding_contributions WHERE contributor_id = ? ORDER BY id DESC LIMIT 100")
                .param(user.id()).query(Long.class).list();
        List<Long> sids = jdbc.sql("SELECT id FROM funding_subscriptions WHERE contributor_id = ? ORDER BY (status = 'ACTIVE') DESC, id DESC LIMIT 100")
                .param(user.id()).query(Long.class).list();
        List<PoolRow> open = jdbc.sql(POOL_SQL + """

                         JOIN community_members m ON m.community_id = p.community_id AND m.user_id = ?
                        WHERE p.status = 'OPEN' AND p.period_end > now() ORDER BY p.period_end LIMIT 20""")
                .param(user.id()).query(this::poolRow).list();
        return new MyFunding(status(), cids.stream().map(this::contribution).toList(), sids.stream().map(this::subscription).toList(),
                summaries(open));
    }

    // ------------------------------------------------------------------ allocation

    /** One moderator's share as computed by a policy. */
    record Planned(long moderatorId, long amount, Integer score, Integer reports, Integer appeals, String note) {}

    record Plan(String policy, List<Planned> rows, String note, Instant from, Instant to) {}

    /** Per-moderator activity inside [from, to) for one community. */
    record Activity(int reportsResolved, int appealsHandled, int actions) {}

    Map<Long, Activity> activity(long communityId, Instant from, Instant to) {
        Map<Long, int[]> acc = new HashMap<>();
        // Distinct report targets resolved by the moderator, counting only targets someone else reported.
        jdbc.sql("""
                        SELECT resolved_by, count(DISTINCT (post_id, coalesce(comment_id, 0))) FROM reports
                        WHERE community_id = ? AND status = 'RESOLVED' AND resolved_by IS NOT NULL AND reporter_id <> resolved_by
                          AND resolved_at >= ? AND resolved_at < ?
                        GROUP BY resolved_by""")
                .params(communityId, Timestamp.from(from), Timestamp.from(to))
                .query((rs, i) -> acc.computeIfAbsent(rs.getLong(1), k -> new int[3])[0] = rs.getInt(2)).list();
        jdbc.sql("""
                        SELECT ap.resolved_by, count(*) FROM appeals ap JOIN moderation_actions m ON m.id = ap.action_id
                        WHERE m.community_id = ? AND ap.resolved_by IS NOT NULL AND ap.resolved_at >= ? AND ap.resolved_at < ?
                        GROUP BY ap.resolved_by""")
                .params(communityId, Timestamp.from(from), Timestamp.from(to))
                .query((rs, i) -> acc.computeIfAbsent(rs.getLong(1), k -> new int[3])[1] = rs.getInt(2)).list();
        jdbc.sql("""
                        SELECT moderator_id, count(*) FROM moderation_actions
                        WHERE community_id = ? AND moderator_id IS NOT NULL AND created_at >= ? AND created_at < ?
                        GROUP BY moderator_id""")
                .params(communityId, Timestamp.from(from), Timestamp.from(to))
                .query((rs, i) -> acc.computeIfAbsent(rs.getLong(1), k -> new int[3])[2] = rs.getInt(2)).list();
        Map<Long, Activity> out = new HashMap<>();
        acc.forEach((k, v) -> out.put(k, new Activity(v[0], v[1], v[2])));
        return out;
    }

    /** Computes allocations for EQUAL and ACTIVITY. MANUAL returns zero rows (the owner decides). */
    Plan plan(PoolRow p, String policy, int cap, long total, Instant to) {
        Instant from = p.periodStart();
        if (to.isBefore(from)) to = from;
        List<Long> mods = memberships.moderators(p.communityId()).stream().map(MembershipDao.MemberRow::userId).sorted().toList();
        Map<Long, Activity> act = activity(p.communityId(), from, to);
        List<Planned> rows = new ArrayList<>();
        String note = null;
        Map<Long, Long> split;
        switch (policy) {
            case "ACTIVITY" -> {
                Map<Long, Long> weights = new LinkedHashMap<>();
                for (long m : mods) {
                    Activity a = act.getOrDefault(m, new Activity(0, 0, 0));
                    weights.put(m, (long) AllocationMath.activityScore(a.reportsResolved(), a.appealsHandled(), cap));
                }
                split = AllocationMath.weighted(total, weights);
                if (split.isEmpty()) {
                    note = "No moderator resolved reports or handled appeals in this period, so the pool was split equally among the current moderators.";
                    split = AllocationMath.equal(total, mods);
                }
            }
            case "EQUAL" -> {
                List<Long> active = mods.stream().filter(m -> act.getOrDefault(m, new Activity(0, 0, 0)).actions() > 0).toList();
                if (active.isEmpty()) {
                    note = "No moderator recorded a moderation action in this period, so the pool was split equally among all current moderators.";
                    active = mods;
                }
                split = AllocationMath.equal(total, active);
            }
            default -> split = Map.of();
        }
        for (var e : split.entrySet()) {
            Activity a = act.getOrDefault(e.getKey(), new Activity(0, 0, 0));
            boolean activity = "ACTIVITY".equals(policy);
            rows.add(new Planned(e.getKey(), e.getValue(),
                    activity ? AllocationMath.activityScore(a.reportsResolved(), a.appealsHandled(), cap) : null,
                    a.reportsResolved(), a.appealsHandled(), null));
        }
        return new Plan(policy, rows, note, from, to);
    }

    @Transactional(readOnly = true)
    public AllocationPreview preview(long poolId, AuthUser user) {
        PoolRow p = pool(poolId);
        Community c = communities.findById(p.communityId()).orElseThrow(() -> ApiException.notFound("Pool"));
        access.requireModerator(c, user);
        if ("CLOSED".equals(p.status())) throw ApiException.badRequest("This pool is already closed; see its transparency report");
        Policy pol = policy(c.getId());
        long total = raised(p.id());
        Instant to = min(Instant.now(), p.periodEnd());
        Plan plan = plan(p, pol.policy(), pol.activityCap(), total, to);
        Map<Long, UserSummary> u = directory.summaries(plan.rows().stream().map(Planned::moderatorId).toList());
        String note = "MANUAL".equals(pol.policy())
                ? "Manual allocation: the owner enters each moderator's amount and a public note when the pool closes."
                : plan.note();
        return new AllocationPreview(pol.policy(), money(total, p), plan.rows().stream()
                .map(r -> new Allocation(u.get(r.moderatorId()), pol.policy(), money(r.amount(), p), r.score(), r.reports(), r.appeals(), null, null,
                        null))
                .toList(), note, plan.from(), plan.to());
    }

    /** Owner closes a pool now (or allocates a MANUAL pool whose period ended). */
    @Transactional
    public TransparencyReport close(long poolId, AuthUser user, CloseRequest req) {
        runtime.require();
        PoolRow p = lockPool(poolId);
        Community c = communities.findById(p.communityId()).orElseThrow(() -> ApiException.notFound("Pool"));
        requireOwner(c, user);
        closeLocked(p, user.id(), req, Instant.now());
        return report(poolId, user);
    }

    /** Scheduler: closes a pool whose period is over. MANUAL pools move to AWAITING_ALLOCATION for the owner. */
    @Transactional
    public void closeIfDue(long poolId, Instant now) {
        PoolRow p = lockPool(poolId);
        if (!"OPEN".equals(p.status()) || p.periodEnd().isAfter(now)) return;
        closeLocked(p, null, null, now);
    }

    @Transactional(readOnly = true)
    public List<Long> duePools(Instant now, int limit) {
        return jdbc.sql("SELECT id FROM funding_pools WHERE status = 'OPEN' AND period_end <= ? ORDER BY period_end LIMIT ?")
                .params(Timestamp.from(now), limit).query(Long.class).list();
    }

    private void closeLocked(PoolRow p, Long closedBy, CloseRequest req, Instant now) {
        if ("CLOSED".equals(p.status())) throw ApiException.badRequest("This pool is already closed");
        Instant to = min(now, p.periodEnd());
        // No more charges once the period is over. Provider-billed subscriptions are cancelled at the provider too.
        cancelAtProvider(p.id());
        jdbc.sql("UPDATE funding_subscriptions SET status = 'ENDED', next_charge_at = NULL, cancelled_at = now(), cancelled_by = 'SYSTEM' "
                + "WHERE pool_id = ? AND status = 'ACTIVE'").param(p.id()).update();
        Policy pol = policy(p.communityId());
        long total = raised(p.id());
        List<Planned> rows;
        String note;
        if ("MANUAL".equals(pol.policy())) {
            if (req == null || req.allocations() == null) {
                if (closedBy != null) throw ApiException.field("allocations", "Manual policy: enter each moderator's amount");
                if ("OPEN".equals(p.status())) {
                    jdbc.sql("UPDATE funding_pools SET status = 'AWAITING_ALLOCATION' WHERE id = ?").param(p.id()).update();
                }
                return;
            }
            rows = manual(p, total, req);
            note = req.note().strip();
        } else {
            Plan plan = plan(p, pol.policy(), pol.activityCap(), total, to);
            rows = plan.rows();
            note = plan.note();
        }
        for (Planned r : rows) {
            long aid = jdbc.sql("""
                            INSERT INTO funding_allocations (pool_id, moderator_id, policy, amount_minor, currency, activity_score,
                              reports_resolved, appeals_handled, note, demo, mode)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                    .params(p.id(), r.moderatorId(), pol.policy(), r.amount(), p.currency(), r.score(), r.reports(), r.appeals(), r.note(),
                            p.demo(), p.mode())
                    .query(Long.class).single();
            if (r.amount() > 0) {
                jdbc.sql("""
                                INSERT INTO funding_ledger (pool_id, entry_type, debit_account, credit_account, amount_minor, currency, allocation_id, demo, mode)
                                VALUES (?, 'ALLOCATION', ?, ?, ?, ?, ?, ?, ?)""")
                        .params(p.id(), poolAccount(p.id()), "moderator:" + r.moderatorId(), r.amount(), p.currency(), aid, p.demo(), p.mode()).update();
            }
        }
        long allocated = rows.stream().mapToLong(Planned::amount).sum();
        if (allocated != total) throw new IllegalStateException("Allocation of pool " + p.id() + " doesn't add up: " + allocated + " != " + total);
        // Stripe pools: one payout per positive allocation, sent after this transaction commits (D-046).
        payouts.createForPool(p.id(), p.provider(), p.mode());
        jdbc.sql("UPDATE funding_pools SET status = 'CLOSED', closed_policy = ?, closed_note = ?, closed_by = ?, closed_at = ? WHERE id = ?")
                .params(pol.policy(), note, closedBy, Timestamp.from(now), p.id()).update();
    }

    /**
     * Cancels the pool's still-active provider-billed subscriptions at the provider, so nobody is billed for a closed pool.
     * A failure is logged and doesn't block the close; a renewal that arrives for a closed pool is not recorded (D-035).
     */
    private void cancelAtProvider(long poolId) {
        PaymentProvider provider = runtime.provider();
        if (provider == null || !provider.hostedCheckout()) return;
        List<String> refs = jdbc.sql("SELECT provider_ref FROM funding_subscriptions WHERE pool_id = ? AND status = 'ACTIVE' AND provider = ?")
                .params(poolId, provider.id()).query(String.class).list();
        for (String ref : refs) {
            try {
                provider.cancel(ref);
            } catch (RuntimeException e) {
                log.warn("Cancelling a provider subscription of closed pool {} failed; cancel it in the provider dashboard: {}", poolId, e.getMessage());
            }
        }
    }

    private List<Planned> manual(PoolRow p, long total, CloseRequest req) {
        if (req.note() == null || req.note().strip().length() < 10) {
            throw ApiException.field("note", "Manual allocation needs a public note explaining it (at least 10 characters)");
        }
        Set<Long> mods = memberships.moderators(p.communityId()).stream().map(MembershipDao.MemberRow::userId).collect(Collectors.toSet());
        Map<Long, Long> amounts = new LinkedHashMap<>();
        for (ManualAllocation a : req.allocations()) {
            User u = users.findByUsernameIgnoreCase(a.username()).filter(x -> x.getDeletedAt() == null)
                    .orElseThrow(() -> ApiException.field("allocations", "No user named " + a.username()));
            if (!mods.contains(u.getId())) throw ApiException.field("allocations", u.getUsername() + " isn't a moderator here");
            if (amounts.put(u.getId(), a.amountMinor()) != null) throw ApiException.field("allocations", u.getUsername() + " is listed twice");
        }
        long sum = 0;
        for (long v : amounts.values()) sum = Math.addExact(sum, v);
        if (sum != total) {
            throw ApiException.field("allocations", "Allocations must add up to exactly the amount raised (" + total + "), not " + sum);
        }
        if (amounts.isEmpty() && total > 0) throw ApiException.field("allocations", "Allocate the pool to at least one moderator");
        String note = req.note().strip();
        List<Planned> rows = new ArrayList<>();
        amounts.forEach((id, amt) -> rows.add(new Planned(id, amt, null, null, null, note)));
        return rows;
    }

    // ------------------------------------------------------------------ transparency report

    @Transactional(readOnly = true)
    public TransparencyReport report(long poolId, AuthUser user) {
        PoolRow p = pool(poolId);
        Community c = communities.findById(p.communityId()).orElseThrow(() -> ApiException.notFound("Pool"));
        if (!access.canView(c, access.roleOf(c, user), user)) throw ApiException.notFound("Pool");
        PoolSummary summary = summaries(List.of(p)).getFirst();

        record Totals(long count, long contributors, long sum) {}
        Totals t = jdbc.sql("SELECT count(*), count(DISTINCT contributor_id), coalesce(sum(amount_minor), 0) FROM funding_contributions WHERE pool_id = ?")
                .param(p.id()).query((rs, i) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3))).single();
        List<Long> named = jdbc.sql("""
                        SELECT contributor_id FROM funding_contributions WHERE pool_id = ? AND show_name AND contributor_id IS NOT NULL
                        GROUP BY contributor_id ORDER BY min(id)""")
                .param(p.id()).query(Long.class).list();
        Map<Long, UserSummary> namedUsers = directory.summaries(named);
        List<UserSummary> namedList = named.stream().map(namedUsers::get).filter(Objects::nonNull).toList();

        record A(long modId, String policy, long amount, Integer score, Integer reports, Integer appeals, String note, Instant at) {}
        List<A> allocs = jdbc.sql("""
                        SELECT moderator_id, policy, amount_minor, activity_score, reports_resolved, appeals_handled, note, created_at
                        FROM funding_allocations WHERE pool_id = ? ORDER BY amount_minor DESC, moderator_id""")
                .param(p.id())
                .query((rs, i) -> new A(rs.getLong(1), rs.getString(2), rs.getLong(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                        (Integer) rs.getObject(6), rs.getString(7), ts(rs, 8)))
                .list();
        Map<Long, UserSummary> mods = directory.summaries(allocs.stream().map(A::modId).toList());
        Map<Long, ConnectDtos.AllocationPayout> paid = payouts.forPool(p.id());
        List<Allocation> allocations = allocs.stream().map(a -> new Allocation(mods.get(a.modId()), a.policy(), money(a.amount(), p), a.score(),
                a.reports(), a.appeals(), a.note(), a.at(), paid.get(a.modId()))).toList();
        long allocated = allocs.stream().mapToLong(A::amount).sum();

        record L(long id, String type, String debit, String credit, long amount, Long contributionId, Instant at) {}
        List<L> ledgerRows = jdbc.sql("""
                        SELECT id, entry_type, debit_account, credit_account, amount_minor, contribution_id, created_at
                        FROM funding_ledger WHERE pool_id = ? ORDER BY id LIMIT ?""")
                .params(p.id(), LEDGER_LIMIT + 1)
                .query((rs, i) -> new L(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                        (Long) rs.getObject(6), ts(rs, 7)))
                .list();
        boolean truncated = ledgerRows.size() > LEDGER_LIMIT;
        if (truncated) ledgerRows = ledgerRows.subList(0, LEDGER_LIMIT);
        // Contributor names appear only for people who opted in.
        Map<Long, Long> namedByContribution = new HashMap<>();
        List<Long> cids = ledgerRows.stream().map(L::contributionId).filter(Objects::nonNull).toList();
        if (!cids.isEmpty()) {
            jdbc.sql("SELECT id, contributor_id FROM funding_contributions WHERE id IN (:ids) AND show_name AND contributor_id IS NOT NULL")
                    .param("ids", cids).query((rs, i) -> namedByContribution.put(rs.getLong(1), rs.getLong(2))).list();
        }
        Set<Long> userIds = new HashSet<>(namedByContribution.values());
        ledgerRows.forEach(l -> Stream.of(l.debit(), l.credit()).filter(a -> a.startsWith("moderator:") || a.startsWith("connected:"))
                .forEach(a -> userIds.add(Long.parseLong(a.substring(a.indexOf(':') + 1)))));
        Map<Long, UserSummary> labels = directory.summaries(userIds);
        List<LedgerEntry> ledger = ledgerRows.stream().map(l -> {
            String debitLabel = l.contributionId() != null
                    ? Optional.ofNullable(namedByContribution.get(l.contributionId())).map(labels::get).map(u -> "u/" + u.username())
                    .orElse("Anonymous contributor")
                    : accountLabel(l.debit(), labels, p);
            return new LedgerEntry(l.id(), l.type(), l.debit(), debitLabel,
                    l.credit(), accountLabel(l.credit(), labels, p), money(l.amount(), p), l.at());
        }).toList();

        record R(long count, long sum) {}
        R refunds = jdbc.sql("SELECT count(*), coalesce(sum(amount_minor), 0) FROM funding_refunds WHERE pool_id = ?").param(p.id())
                .query((rs, i) -> new R(rs.getLong(1), rs.getLong(2))).single();
        return new TransparencyReport(summary, money(t.sum() - refunds.sum(), p), t.count(), t.contributors(), t.contributors() - namedList.size(),
                namedList, money(allocated, p), allocations, ledger, truncated, reconcile(p), p.closedNote(), money(refunds.sum(), p), refunds.count(),
                payouts.reconcile(p.id(), p.provider(), p.mode(), p.status(), p.currency()));
    }

    /** Cross-checks the contribution, allocation and ledger tables against each other with independent sums. */
    Reconciliation reconcile(PoolRow p) {
        long contributions = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_contributions WHERE pool_id = ?").param(p.id())
                .query(Long.class).single();
        long refunds = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_refunds WHERE pool_id = ?").param(p.id())
                .query(Long.class).single();
        long allocations = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_allocations WHERE pool_id = ?").param(p.id())
                .query(Long.class).single();
        long in = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_ledger WHERE pool_id = ? AND credit_account = ?")
                .params(p.id(), poolAccount(p.id())).query(Long.class).single();
        long out = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_ledger WHERE pool_id = ? AND debit_account = ?")
                .params(p.id(), poolAccount(p.id())).query(Long.class).single();
        long balance = in - out;
        record S(String status, Instant closedAt) {}
        S st = jdbc.sql("SELECT status, closed_at FROM funding_pools WHERE id = ?").param(p.id())
                .query((rs, i) -> new S(rs.getString(1), ts(rs, 2))).single();
        // Refunds after the close reverse money that was already allocated (as an obligation): that part is a shortfall.
        long shortfall = st.closedAt() == null ? 0 : jdbc.sql("""
                        SELECT coalesce(sum(amount_minor), 0) FROM funding_refunds WHERE pool_id = ? AND created_at > ?""")
                .params(p.id(), Timestamp.from(st.closedAt())).query(Long.class).single();
        boolean ok = contributions == in && allocations + refunds == out
                && ("CLOSED".equals(st.status()) ? balance == -shortfall : balance >= 0);
        return new Reconciliation(money(contributions, p), money(in, p), money(out, p), money(allocations, p), money(balance, p), ok,
                money(refunds, p), money(shortfall, p));
    }

    private static String accountLabel(String account, Map<Long, UserSummary> users, PoolRow p) {
        if (account.equals(CONTRIBUTORS_ACCOUNT)) return "Contributors";
        if (account.equals(poolAccount(p.id()))) return "Pool: " + p.title();
        if (account.startsWith("moderator:")) {
            UserSummary u = users.get(Long.parseLong(account.substring("moderator:".length())));
            return u == null ? "Moderator (account deleted)" : "u/" + u.username();
        }
        if (account.startsWith("connected:")) {
            UserSummary u = users.get(Long.parseLong(account.substring("connected:".length())));
            return (u == null ? "Moderator" : "u/" + u.username()) + "'s Stripe account" + (p.demo() ? " (test mode)" : "");
        }
        return account;
    }

    // ------------------------------------------------------------------ webhooks

    /**
     * Verifies, de-duplicates and applies a provider webhook. The event id is recorded in the same transaction as its
     * effect, so a replay is ignored, and a failure rolls both back so the provider's retry is processed.
     */
    @Transactional
    public WebhookResult webhook(String providerId, String payload, String signature) {
        PaymentProvider provider = runtime.require();
        if (!provider.id().equals(providerId)) throw ApiException.notFound("Webhook endpoint");
        WebhookEvent event;
        try {
            event = provider.verifyWebhook(payload, signature);
        } catch (WebhookVerificationException e) {
            throw ApiException.badRequest("Invalid webhook: " + e.getMessage());
        }
        if (!claimEvent(provider.id(), event)) return new WebhookResult("duplicate", event.id());
        String outcome = switch (event.type()) {
            case "charge.succeeded" -> {
                Object chargeId = event.data().get("chargeId");
                boolean known = chargeId instanceof String s
                        && jdbc.sql("SELECT count(*) FROM funding_contributions WHERE provider = ? AND provider_charge_id = ?")
                        .params(provider.id(), s).query(Long.class).single() > 0;
                yield known ? "charge confirmed" : "unknown charge";
            }
            case "subscription.cancelled" -> {
                Object ref = event.data().get("subscriptionRef");
                int n = ref instanceof String s ? jdbc.sql("""
                                UPDATE funding_subscriptions SET status = 'CANCELLED', next_charge_at = NULL, cancelled_at = now(),
                                  cancelled_by = 'PROVIDER' WHERE provider = ? AND provider_ref = ? AND status = 'ACTIVE'""")
                        .params(provider.id(), s).update() : 0;
                yield n > 0 ? "subscription cancelled" : "no active subscription";
            }
            default -> "ignored";
        };
        recordOutcome(provider.id(), event.id(), outcome);
        return new WebhookResult("processed", event.id());
    }

    /**
     * Records an event id for a provider. False if it was already there (a replay or a provider retry of a processed
     * event). Must run in the same transaction as the event's effect, so a failure rolls both back and the retry applies it.
     */
    boolean claimEvent(String providerId, WebhookEvent event) {
        return jdbc.sql("INSERT INTO funding_webhook_events (provider, event_id, event_type) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                .params(providerId, event.id(), event.type().length() > 60 ? event.type().substring(0, 60) : event.type()).update() > 0;
    }

    void recordOutcome(String providerId, String eventId, String outcome) {
        jdbc.sql("UPDATE funding_webhook_events SET outcome = ? WHERE provider = ? AND event_id = ?")
                .params(outcome.length() > 200 ? outcome.substring(0, 200) : outcome, providerId, eventId).update();
    }

    // ------------------------------------------------------------------ rows and mapping

    private static final String POOL_SQL = """
            SELECT p.id, p.community_id, p.created_by, p.title, p.purpose, p.currency, p.goal_minor, p.period_start, p.period_end,
              p.status, p.provider, p.demo, p.mode, p.closed_policy, p.closed_note, p.closed_by, p.closed_at, p.created_at,
              coalesce((SELECT sum(c.amount_minor) FROM funding_contributions c WHERE c.pool_id = p.id), 0)
                - coalesce((SELECT sum(r.amount_minor) FROM funding_refunds r WHERE r.pool_id = p.id), 0) AS raised,
              (SELECT count(DISTINCT c.contributor_id) FROM funding_contributions c WHERE c.pool_id = p.id) AS contributors
            FROM funding_pools p""";

    record PoolRow(long id, long communityId, Long createdBy, String title, String purpose, String currency, long goal, Instant periodStart,
                   Instant periodEnd, String status, String provider, boolean demo, String mode, String closedPolicy, String closedNote, Long closedBy,
                   Instant closedAt, Instant createdAt, long raised, long contributors) {}

    private PoolRow poolRow(ResultSet rs, int i) throws SQLException {
        return new PoolRow(rs.getLong("id"), rs.getLong("community_id"), (Long) rs.getObject("created_by"), rs.getString("title"),
                rs.getString("purpose"), rs.getString("currency"), rs.getLong("goal_minor"), ts(rs, "period_start"), ts(rs, "period_end"),
                rs.getString("status"), rs.getString("provider"), rs.getBoolean("demo"), rs.getString("mode"), rs.getString("closed_policy"),
                rs.getString("closed_note"), (Long) rs.getObject("closed_by"), ts(rs, "closed_at"), ts(rs, "created_at"),
                rs.getLong("raised"), rs.getLong("contributors"));
    }

    PoolRow pool(long id) {
        return jdbc.sql(POOL_SQL + " WHERE p.id = ?").param(id).query(this::poolRow).optional()
                .orElseThrow(() -> ApiException.notFound("Pool"));
    }

    PoolRow lockPool(long id) {
        jdbc.sql("SELECT id FROM funding_pools WHERE id = ? FOR UPDATE").param(id).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("Pool"));
        return pool(id);
    }

    /** Net amount raised: confirmed contributions minus refunds. This is what a close allocates. */
    private long raised(long poolId) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT sum(amount_minor) FROM funding_contributions WHERE pool_id = ?), 0)
                          - coalesce((SELECT sum(amount_minor) FROM funding_refunds WHERE pool_id = ?), 0)""")
                .params(poolId, poolId).query(Long.class).single();
    }

    private List<PoolSummary> summaries(List<PoolRow> rows) {
        Map<Long, CommunityRef> refs = refs(rows.stream().map(PoolRow::communityId).toList());
        Map<Long, UserSummary> u = directory.summaries(rows.stream().map(PoolRow::createdBy).toList());
        return rows.stream().map(p -> new PoolSummary(p.id(), refs.get(p.communityId()), p.title(), p.purpose(), money(p.goal(), p),
                money(p.raised(), p), p.contributors(), p.periodStart(), p.periodEnd(), p.status(), p.provider(), p.demo(), p.mode(),
                p.createdBy() == null ? null : u.get(p.createdBy()), p.closedPolicy(), p.closedAt(), p.createdAt())).toList();
    }

    private Map<Long, CommunityRef> refs(Collection<Long> ids) {
        Map<Long, CommunityRef> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        communities.findByIdIn(new HashSet<>(ids)).forEach(c -> out.put(c.getId(), CommunityMapper.ref(c)));
        return out;
    }

    PoolRef poolRef(long poolId) {
        PoolRow p = pool(poolId);
        return new PoolRef(p.id(), p.title(), refs(List.of(p.communityId())).get(p.communityId()), p.status());
    }

    Contribution contribution(long id) {
        record C(long poolId, String kind, long amount, String currency, boolean show, Long sub, Integer cycle, String charge,
                 String provider, String mode, Instant at) {}
        C c = jdbc.sql("""
                        SELECT pool_id, kind, amount_minor, currency, show_name, subscription_id, cycle, provider_charge_id, provider, mode, created_at
                        FROM funding_contributions WHERE id = ?""")
                .param(id)
                .query((rs, i) -> new C(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getBoolean(5),
                        (Long) rs.getObject(6), (Integer) rs.getObject(7), rs.getString(8), rs.getString(9), rs.getString(10), ts(rs, 11)))
                .single();
        return new Contribution(id, poolRef(c.poolId()), c.kind(), Money.of(c.amount(), c.currency(), c.mode()), c.show(), c.sub(),
                c.cycle(), c.charge(), c.provider(), Money.of(refundedOf(id), c.currency(), c.mode()), c.at());
    }

    SubscriptionDto subscription(long id) {
        record S(long poolId, long amount, String currency, boolean show, String status, int cycles, Instant next, Instant at,
                 Instant cancelled, String by, String provider, String mode, Instant failed) {}
        S s = jdbc.sql("""
                        SELECT pool_id, amount_minor, currency, show_name, status, cycles_charged, next_charge_at, created_at, cancelled_at,
                          cancelled_by, provider, mode, payment_failed_at
                        FROM funding_subscriptions WHERE id = ?""")
                .param(id)
                .query((rs, i) -> new S(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), rs.getInt(6),
                        ts(rs, 7), ts(rs, 8), ts(rs, 9), rs.getString(10), rs.getString(11), rs.getString(12), ts(rs, 13)))
                .single();
        String billedBy = DemoPaymentProvider.ID.equals(s.provider()) ? "howlers" : "provider";
        return new SubscriptionDto(id, poolRef(s.poolId()), Money.of(s.amount(), s.currency(), s.mode()), s.show(), s.status(), s.cycles(),
                s.next(), s.at(), s.cancelled(), s.by(), s.provider(), billedBy, s.failed());
    }

    static Money money(long amount, PoolRow p) {
        return Money.of(amount, p.currency(), p.mode());
    }

    static String poolAccount(long poolId) {
        return "pool:" + poolId;
    }

    // ------------------------------------------------------------------ permissions

    private Community viewable(String slug, AuthUser user) {
        Community c = communities.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Community"));
        access.requireView(c, user);
        return c;
    }

    private static boolean isOwner(Optional<CommunityRole> role, AuthUser user) {
        return role.map(r -> r == CommunityRole.OWNER).orElse(false) || (user != null && user.isAdmin());
    }

    private void requireOwner(Community c, AuthUser user) {
        if (!isOwner(access.roleOf(c, user), user)) throw ApiException.forbidden("Only the community owner can do that");
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    static Instant ts(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}

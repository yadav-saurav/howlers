package app.howlers.funding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityRole;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.funding.FundingDtos.Allocation;
import app.howlers.funding.FundingDtos.CloseRequest;
import app.howlers.funding.FundingDtos.ContributeRequest;
import app.howlers.funding.FundingDtos.ContributeResult;
import app.howlers.funding.FundingDtos.CreatePoolRequest;
import app.howlers.funding.FundingDtos.LedgerEntry;
import app.howlers.funding.FundingDtos.ManualAllocation;
import app.howlers.funding.FundingDtos.PolicyRequest;
import app.howlers.funding.FundingDtos.PoolSummary;
import app.howlers.funding.FundingDtos.SubscriptionDto;
import app.howlers.funding.FundingDtos.TransparencyReport;
import app.howlers.funding.payment.DemoPaymentProvider;
import app.howlers.moderation.ModerationDtos.Action;
import app.howlers.moderation.ModerationDtos.ReportRequest;
import app.howlers.moderation.ModerationService;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class FundingIT extends IntegrationTest {
    private static final String SECRET = "dev-only-demo-webhook-secret-change-me";

    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired ModerationService mod;
    @Autowired FundingService funding;
    @Autowired FundingJobs jobs;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@f.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private record World(AuthUser owner, AuthUser mod1, AuthUser mod2, AuthUser member, AuthUser reporter, AuthUser outsider, String slug) {}

    private World world() {
        String s = Long.toString(System.nanoTime() % 1_000_000);
        AuthUser owner = user("fo" + s), mod1 = user("fa" + s), mod2 = user("fb" + s), member = user("fm" + s), reporter = user("fr" + s),
                outsider = user("fx" + s);
        String slug = "fund" + s;
        communities.create(owner, new CreateCommunityRequest(slug, "Funding test", "", null, "PUBLIC", List.of(), List.of()));
        for (AuthUser u : List.of(mod1, mod2, member, reporter)) communities.join(slug, u);
        communities.setRole(slug, owner, mod1.username(), CommunityRole.MODERATOR);
        communities.setRole(slug, owner, mod2.username(), CommunityRole.MODERATOR);
        return new World(owner, mod1, mod2, member, reporter, outsider, slug);
    }

    private PoolSummary pool(World w, AuthUser by) {
        return funding.createPool(w.slug(), by, new CreatePoolRequest("Hosting and tools", "Pays for the mod bot hosting and tools.",
                "USD", 50_000L, null, Instant.now().plus(Duration.ofDays(90))));
    }

    private long newPost(World w, String title) {
        return posts.create(w.member(), new CreatePostRequest(w.slug(), "TEXT", title, "<p>body</p>", null, null, null, null, null));
    }

    private static void assertForbidden(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** Independent re-computation from the report's own rows: every total has to agree with the ledger. */
    private static void assertReconciles(TransparencyReport r) {
        long ledgerIn = r.ledger().stream().filter(l -> l.entryType().equals("CONTRIBUTION")).mapToLong(l -> l.amount().amountMinor()).sum();
        long ledgerOut = r.ledger().stream().filter(l -> l.entryType().equals("ALLOCATION")).mapToLong(l -> l.amount().amountMinor()).sum();
        long allocations = r.allocations().stream().mapToLong(a -> a.amount().amountMinor()).sum();
        assertThat(ledgerIn).isEqualTo(r.raised().amountMinor()).isEqualTo(r.pool().raised().amountMinor());
        assertThat(ledgerOut).isEqualTo(allocations).isEqualTo(r.allocated().amountMinor());
        assertThat(r.reconciliation().contributionsTotal().amountMinor()).isEqualTo(ledgerIn);
        assertThat(r.reconciliation().ledgerIn().amountMinor()).isEqualTo(ledgerIn);
        assertThat(r.reconciliation().ledgerOut().amountMinor()).isEqualTo(ledgerOut);
        assertThat(r.reconciliation().poolBalance().amountMinor()).isEqualTo(ledgerIn - ledgerOut);
        if (r.pool().status().equals("CLOSED")) assertThat(ledgerIn).isEqualTo(ledgerOut);
        assertThat(r.reconciliation().reconciles()).isTrue();
        // Every ledger row moves money between two different accounts, and contributions always land in this pool.
        for (LedgerEntry l : r.ledger()) {
            assertThat(l.debitAccount()).isNotEqualTo(l.creditAccount());
            assertThat(l.amount().demo()).isTrue();
            assertThat(l.amount().label()).isEqualTo(Money.DEMO_LABEL);
        }
    }

    private Map<Long, Long> amounts(List<Allocation> allocations) {
        return allocations.stream().collect(Collectors.toMap(a -> a.moderator().id(), a -> a.amount().amountMinor()));
    }

    @Test
    void onlyModeratorsCreatePoolsAndOnlyTheOwnerSetsPolicy() {
        World w = world();
        CreatePoolRequest req = new CreatePoolRequest("Tools", "Pays for moderation tools.", "USD", 10_000L, null,
                Instant.now().plus(Duration.ofDays(30)));
        assertForbidden(() -> funding.createPool(w.slug(), w.member(), req));
        assertForbidden(() -> funding.createPool(w.slug(), w.outsider(), req));
        PoolSummary p = funding.createPool(w.slug(), w.mod1(), req);
        assertThat(p.status()).isEqualTo("OPEN");
        assertThat(p.demo()).isTrue();
        assertThat(p.goal().label()).isEqualTo(Money.DEMO_LABEL);
        assertThat(p.raised().amountMinor()).isZero();

        // Validation: currency, period length.
        assertThatThrownBy(() -> funding.createPool(w.slug(), w.mod1(), new CreatePoolRequest("T", "Pays for moderation tools.", "XYZ",
                100L, null, Instant.now().plus(Duration.ofDays(30))))).isInstanceOf(ApiException.class).hasMessageContaining("USD");
        assertThatThrownBy(() -> funding.createPool(w.slug(), w.mod1(), new CreatePoolRequest("T", "Pays for moderation tools.", "USD",
                1000L, null, Instant.now().plus(Duration.ofHours(2))))).isInstanceOf(ApiException.class);

        assertForbidden(() -> funding.setPolicy(w.slug(), w.mod1(), new PolicyRequest("ACTIVITY", 10)));
        assertForbidden(() -> funding.setPolicy(w.slug(), w.member(), new PolicyRequest("ACTIVITY", 10)));
        assertThat(funding.setPolicy(w.slug(), w.owner(), new PolicyRequest("ACTIVITY", 10)).policy()).isEqualTo("ACTIVITY");

        var view = funding.community(w.slug(), w.mod1());
        assertThat(view.viewer().canCreatePool()).isTrue();
        assertThat(view.viewer().canSetPolicy()).isFalse();
        assertThat(view.policy().activityCap()).isEqualTo(10);
        assertThat(funding.community(w.slug(), w.owner()).viewer().canSetPolicy()).isTrue();
        assertThat(funding.community(w.slug(), w.outsider()).viewer().canContribute()).isFalse();
        assertThat(funding.community(w.slug(), null).status().label()).isEqualTo(Money.DEMO_LABEL);

        // Members contribute; non-members and banned members can't. Only the owner closes.
        assertForbidden(() -> funding.contribute(p.id(), w.outsider(), new ContributeRequest(500L, false, false)));
        mod.ban(w.slug(), w.mod1(), w.reporter().username(), "Spam", 7);
        assertForbidden(() -> funding.contribute(p.id(), w.reporter(), new ContributeRequest(500L, false, false)));
        assertThatThrownBy(() -> funding.contribute(p.id(), w.member(), new ContributeRequest(50L, false, false)))
                .isInstanceOf(ApiException.class).hasMessageContaining("between");
        assertForbidden(() -> funding.close(p.id(), w.mod1(), null));
        assertForbidden(() -> funding.preview(p.id(), w.member()));
    }

    @Test
    void oneTimeAndMonthlyContributionsRecurringJobAndCancel() {
        World w = world();
        PoolSummary p = pool(w, w.mod1());
        ContributeResult once = funding.contribute(p.id(), w.member(), new ContributeRequest(500L, false, false));
        assertThat(once.contribution().kind()).isEqualTo("ONE_TIME");
        assertThat(once.contribution().amount().demo()).isTrue();
        assertThat(once.contribution().chargeId()).startsWith("demo_ch_");
        assertThat(once.subscription()).isNull();

        ContributeResult monthly = funding.contribute(p.id(), w.member(), new ContributeRequest(300L, true, true));
        SubscriptionDto sub = monthly.subscription();
        assertThat(sub.status()).isEqualTo("ACTIVE");
        assertThat(sub.cyclesCharged()).isEqualTo(1); // the first month is charged immediately
        assertThat(monthly.contribution().kind()).isEqualTo("RECURRING");
        assertThat(monthly.contribution().cycle()).isEqualTo(1);
        assertThat(sub.nextChargeAt()).isAfter(Instant.now().plus(Duration.ofDays(27)));

        // Not due yet: nothing happens.
        jobs.chargeDue(Instant.now());
        assertThat(subscription(w.member(), sub.id()).cyclesCharged()).isEqualTo(1);
        // A month later the job charges cycle 2, exactly once even if it runs twice.
        Instant later = sub.nextChargeAt().plusSeconds(60);
        jobs.chargeDue(later);
        jobs.chargeDue(later);
        assertThat(funding.chargeCycle(sub.id(), later)).isNull();
        SubscriptionDto afterJob = subscription(w.member(), sub.id());
        assertThat(afterJob.cyclesCharged()).isEqualTo(2);
        assertThat(afterJob.nextChargeAt()).isAfter(later.plus(Duration.ofDays(27)));
        assertThat(jdbc.sql("SELECT count(*) FROM funding_contributions WHERE subscription_id = ?").param(sub.id()).query(Long.class).single())
                .isEqualTo(2);

        // Only the contributor can cancel; cancelling stops future charges and is idempotent.
        assertThatThrownBy(() -> funding.cancelSubscription(sub.id(), w.mod1())).isInstanceOf(ApiException.class);
        SubscriptionDto cancelled = funding.cancelSubscription(sub.id(), w.member());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledBy()).isEqualTo("USER");
        assertThat(cancelled.nextChargeAt()).isNull();
        assertThat(funding.cancelSubscription(sub.id(), w.member()).status()).isEqualTo("CANCELLED");
        jobs.chargeDue(later.plus(Duration.ofDays(40)));
        assertThat(subscription(w.member(), sub.id()).cyclesCharged()).isEqualTo(2);

        var mine = funding.my(w.member());
        assertThat(mine.contributions()).hasSize(3);
        assertThat(mine.contributions()).allSatisfy(c -> assertThat(c.amount().label()).isEqualTo(Money.DEMO_LABEL));
        assertThat(mine.openPools()).extracting(PoolSummary::id).contains(p.id());

        TransparencyReport r = funding.report(p.id(), null);
        assertThat(r.raised().amountMinor()).isEqualTo(500 + 300 + 300);
        assertThat(r.contributionCount()).isEqualTo(3);
        assertThat(r.contributorCount()).isEqualTo(1);
        // The member opted in to show their name on the monthly contribution only.
        assertThat(r.namedContributors()).extracting(u -> u.username()).containsExactly(w.member().username());
        assertThat(r.ledger()).filteredOn(l -> l.entryType().equals("CONTRIBUTION")).extracting(LedgerEntry::debitLabel)
                .containsExactly("Anonymous contributor", "u/" + w.member().username(), "u/" + w.member().username());
        assertReconciles(r);
    }

    private SubscriptionDto subscription(AuthUser u, long id) {
        return funding.my(u).subscriptions().stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }

    @Test
    void activityPolicyCountsResolvedReportsAndAppealsNotRawRemovals() {
        World w = world();
        funding.setPolicy(w.slug(), w.owner(), new PolicyRequest("ACTIVITY", 50));
        PoolSummary p = pool(w, w.mod1());
        long p1 = newPost(w, "One"), p2 = newPost(w, "Two"), p3 = newPost(w, "Three"), p4 = newPost(w, "Four"), p5 = newPost(w, "Five");

        // mod1 resolves three distinct reported targets (p1 has two reports but counts once).
        mod.report(w.reporter(), new ReportRequest("POST", p1, null, "Spam", null));
        mod.report(w.owner(), new ReportRequest("POST", p1, null, "Spam", null));
        mod.report(w.reporter(), new ReportRequest("POST", p2, null, "Off topic", null));
        mod.report(w.reporter(), new ReportRequest("POST", p5, null, "Off topic", null));
        mod.postAction(p1, w.mod1(), Action.REMOVE_POST, "Spam");
        mod.postAction(p2, w.mod1(), Action.APPROVE_POST, null);
        mod.postAction(p5, w.mod1(), Action.APPROVE_POST, null);
        // Self-reported then self-resolved: farming, doesn't count.
        mod.report(w.mod1(), new ReportRequest("POST", p4, null, "Spam", null));
        mod.postAction(p4, w.mod1(), Action.REMOVE_POST, null);
        // mod2 removes without any report (raw removal: zero points) and handles one appeal (two points).
        mod.postAction(p3, w.mod2(), Action.REMOVE_POST, "Low effort");
        long removal = jdbc.sql("SELECT id FROM moderation_actions WHERE post_id = ? AND action = 'REMOVE_POST'").param(p1).query(Long.class).single();
        long appeal = mod.submitAppeal(removal, w.member(), "This was not spam, it was a genuine question.").id();
        mod.resolveAppeal(appeal, w.mod2(), false, "Upheld");

        funding.contribute(p.id(), w.member(), new ContributeRequest(700L, false, false));
        funding.contribute(p.id(), w.reporter(), new ContributeRequest(300L, false, true));

        var preview = funding.preview(p.id(), w.mod2());
        assertThat(preview.policy()).isEqualTo("ACTIVITY");
        assertThat(amounts(preview.allocations())).containsEntry(w.mod1().id(), 600L).containsEntry(w.mod2().id(), 400L)
                .containsEntry(w.owner().id(), 0L);

        TransparencyReport r = funding.close(p.id(), w.owner(), null);
        assertThat(r.pool().status()).isEqualTo("CLOSED");
        assertThat(r.pool().closedPolicy()).isEqualTo("ACTIVITY");
        Map<Long, Allocation> byMod = r.allocations().stream().collect(Collectors.toMap(a -> a.moderator().id(), a -> a));
        assertThat(byMod.get(w.mod1().id()).activityScore()).isEqualTo(3);
        assertThat(byMod.get(w.mod1().id()).reportsResolved()).isEqualTo(3);
        assertThat(byMod.get(w.mod2().id()).activityScore()).isEqualTo(2);
        assertThat(byMod.get(w.mod2().id()).appealsHandled()).isEqualTo(1);
        assertThat(byMod.get(w.owner().id()).activityScore()).isZero();
        assertThat(amounts(r.allocations())).containsEntry(w.mod1().id(), 600L).containsEntry(w.mod2().id(), 400L).containsEntry(w.owner().id(), 0L);
        assertThat(r.allocations()).allSatisfy(a -> assertThat(a.policy()).isEqualTo("ACTIVITY"));
        assertThat(r.ledger()).filteredOn(l -> l.entryType().equals("ALLOCATION")).hasSize(2); // no zero-amount ledger rows
        assertReconciles(r);

        // Closed pools take no more money and can't be closed twice; the ledger can't be edited, even directly.
        assertThatThrownBy(() -> funding.contribute(p.id(), w.member(), new ContributeRequest(500L, false, false))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> funding.close(p.id(), w.owner(), null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE funding_ledger SET amount_minor = 1 WHERE pool_id = ?").param(p.id()).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM funding_allocations WHERE pool_id = ?").param(p.id()).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void equalPolicyClosedByTheJobSplitsWithoutLosingACent() {
        World w = world();
        PoolSummary p = pool(w, w.mod2());
        long a = newPost(w, "Lock me"), b = newPost(w, "Pin me");
        mod.postAction(a, w.mod1(), Action.LOCK, null);
        mod.postAction(b, w.mod2(), Action.PIN, null);
        funding.contribute(p.id(), w.member(), new ContributeRequest(1001L, false, false));
        // The period ends; the owner never touches it.
        jdbc.sql("UPDATE funding_pools SET period_start = period_start - interval '2 days', period_end = now() WHERE id = ?").param(p.id()).update();
        jobs.closeDue(Instant.now());

        TransparencyReport r = funding.report(p.id(), w.member());
        assertThat(r.pool().status()).isEqualTo("CLOSED");
        assertThat(r.pool().closedPolicy()).isEqualTo("EQUAL");
        // Only moderators with activity in the period share; the odd cent goes to the lower user id.
        long low = Math.min(w.mod1().id(), w.mod2().id()), high = Math.max(w.mod1().id(), w.mod2().id());
        assertThat(amounts(r.allocations())).containsOnly(Map.entry(low, 501L), Map.entry(high, 500L));
        assertReconciles(r);
    }

    @Test
    void manualPolicyRequiresANoteAndExactTotals() {
        World w = world();
        funding.setPolicy(w.slug(), w.owner(), new PolicyRequest("MANUAL", null));
        PoolSummary p = pool(w, w.mod1());
        funding.contribute(p.id(), w.member(), new ContributeRequest(1000L, false, false));

        // The job can't decide for the owner: the pool waits for a manual allocation.
        jdbc.sql("UPDATE funding_pools SET period_start = period_start - interval '2 days', period_end = now() WHERE id = ?").param(p.id()).update();
        jobs.closeDue(Instant.now());
        assertThat(funding.report(p.id(), null).pool().status()).isEqualTo("AWAITING_ALLOCATION");

        var good = List.of(new ManualAllocation(w.mod1().username(), 700L), new ManualAllocation(w.mod2().username(), 300L));
        assertThatThrownBy(() -> funding.close(p.id(), w.owner(), null)).isInstanceOf(ApiException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> funding.close(p.id(), w.owner(), new CloseRequest(good, null))).hasMessageContaining("note");
        assertThatThrownBy(() -> funding.close(p.id(), w.owner(), new CloseRequest(good, "short"))).hasMessageContaining("note");
        assertThatThrownBy(() -> funding.close(p.id(), w.owner(), new CloseRequest(
                List.of(new ManualAllocation(w.mod1().username(), 700L)), "mod1 ran the migration this quarter")))
                .hasMessageContaining("add up");
        assertThatThrownBy(() -> funding.close(p.id(), w.owner(), new CloseRequest(
                List.of(new ManualAllocation(w.member().username(), 1000L)), "member helped a lot this quarter")))
                .hasMessageContaining("isn't a moderator");
        assertForbidden(() -> funding.close(p.id(), w.mod1(), new CloseRequest(good, "mod1 ran the migration this quarter")));

        TransparencyReport r = funding.close(p.id(), w.owner(), new CloseRequest(good, "mod1 ran the migration this quarter"));
        assertThat(r.pool().status()).isEqualTo("CLOSED");
        assertThat(r.closedNote()).isEqualTo("mod1 ran the migration this quarter");
        assertThat(r.allocations()).allSatisfy(a -> {
            assertThat(a.policy()).isEqualTo("MANUAL");
            assertThat(a.note()).isEqualTo("mod1 ran the migration this quarter");
        });
        assertThat(amounts(r.allocations())).containsOnly(Map.entry(w.mod1().id(), 700L), Map.entry(w.mod2().id(), 300L));
        assertReconciles(r);
    }

    @Test
    void webhooksAreSignedAndReplaysAreIgnored() throws Exception {
        World w = world();
        PoolSummary p = pool(w, w.mod1());
        SubscriptionDto sub = funding.contribute(p.id(), w.member(), new ContributeRequest(300L, true, false)).subscription();
        String ref = jdbc.sql("SELECT provider_ref FROM funding_subscriptions WHERE id = ?").param(sub.id()).query(String.class).single();
        String eventId = "evt_" + System.nanoTime();
        String payload = "{\"id\":\"" + eventId + "\",\"type\":\"subscription.cancelled\",\"data\":{\"subscriptionRef\":\"" + ref + "\"}}";
        long now = Instant.now().getEpochSecond();
        String sig = DemoPaymentProvider.signatureHeader(SECRET, payload, now);

        // Public endpoint (no JWT), but unsigned, wrongly signed, tampered or stale requests are rejected.
        mvc.perform(post("/api/v1/funding/webhooks/demo").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/funding/webhooks/demo").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Howlers-Demo-Signature", DemoPaymentProvider.signatureHeader("another-secret-of-enough-length", payload, now)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/funding/webhooks/demo").contentType(MediaType.APPLICATION_JSON).content(payload.replace("cancelled", "created"))
                .header("Howlers-Demo-Signature", sig)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/funding/webhooks/demo").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Howlers-Demo-Signature", DemoPaymentProvider.signatureHeader(SECRET, payload, now - 3600)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/funding/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Howlers-Demo-Signature", sig)).andExpect(status().isNotFound());
        assertThat(subscription(w.member(), sub.id()).status()).isEqualTo("ACTIVE");

        mvc.perform(post("/api/v1/funding/webhooks/demo").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Howlers-Demo-Signature", sig))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
        SubscriptionDto after = subscription(w.member(), sub.id());
        assertThat(after.status()).isEqualTo("CANCELLED");
        assertThat(after.cancelledBy()).isEqualTo("PROVIDER");

        // The same event again (a replay, or the provider retrying) is acknowledged but not applied twice.
        mvc.perform(post("/api/v1/funding/webhooks/demo").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Howlers-Demo-Signature", sig))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));
        assertThat(jdbc.sql("SELECT count(*) FROM funding_webhook_events WHERE event_id = ?").param(eventId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT outcome FROM funding_webhook_events WHERE event_id = ?").param(eventId).query(String.class).single())
                .isEqualTo("subscription cancelled");

        // Reads are public for public communities and every amount is labelled.
        mvc.perform(get("/api/v1/funding/pools/" + p.id() + "/report")).andExpect(status().isOk())
                .andExpect(jsonPath("$.raised.label").value(Money.DEMO_LABEL))
                .andExpect(jsonPath("$.reconciliation.reconciles").value(true));
        mvc.perform(get("/api/v1/me/funding")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/funding/pools/" + p.id() + "/allocation-preview")).andExpect(status().isUnauthorized());
    }
}

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
import app.howlers.funding.ConnectDtos.MyPayouts;
import app.howlers.funding.ConnectDtos.OnboardingLink;
import app.howlers.funding.ConnectDtos.PayoutReconciliation;
import app.howlers.funding.FundingDtos.Allocation;
import app.howlers.funding.FundingDtos.ContributeRequest;
import app.howlers.funding.FundingDtos.CreatePoolRequest;
import app.howlers.funding.FundingDtos.LedgerEntry;
import app.howlers.funding.FundingDtos.PoolSummary;
import app.howlers.funding.FundingDtos.TransparencyReport;
import app.howlers.funding.payment.StripeGateway.Transfer;
import app.howlers.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Stripe Connect payouts (D-046, D-047) against a fake gateway: onboarding status, transfers when a pool closes, held
 * shares released on onboarding, idempotency across job reruns and webhook replays, reversals, refunds after payout,
 * ledger reconciliation and permissions. No network.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "howlers.funding.provider=stripe",
        "howlers.funding.stripe.secret-key=sk_test_fake_key_for_the_connect_suite",
        "howlers.funding.stripe.webhook-secret=" + StripeConnectIT.HOOK,
        "howlers.funding.connect.enabled=true",
        "howlers.funding.connect.webhook-secret=" + StripeConnectIT.CONNECT_HOOK,
        "howlers.app.public-url=http://localhost:5173"})
@Import(StripeConnectIT.FakeStripe.class)
class StripeConnectIT extends IntegrationTest {
    static final String HOOK = "whsec_connect_suite_platform_000000";
    static final String CONNECT_HOOK = "whsec_connect_suite_accounts_11111";
    private static final AtomicLong EVENTS = new AtomicLong(System.nanoTime() % 1_000_000);

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeStripe {
        @Bean
        FakeStripeGateway fakeStripeGateway() {
            return new FakeStripeGateway();
        }
    }

    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired FundingService funding;
    @Autowired FundingCheckouts checkouts;
    @Autowired ConnectService connect;
    @Autowired PayoutService payouts;
    @Autowired FundingJobs jobs;
    @Autowired FakeStripeGateway stripe;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private record U(AuthUser user, String token) {}

    private U user(String name) {
        var s = auth.register(new RegisterRequest(name, name + "@c.local", "password1", null), "t", "127.0.0.1").response();
        return new U(new AuthUser(s.user().id(), s.user().username(), s.user().role()), s.accessToken());
    }

    private record World(U owner, U mod, U member, String slug, PoolSummary pool) {}

    private World world() {
        String s = Long.toString(System.nanoTime() % 1_000_000);
        U owner = user("co" + s), mod = user("cm" + s), member = user("cc" + s);
        String slug = "connect" + s;
        communities.create(owner.user(), new CreateCommunityRequest(slug, "Connect payouts test", "", null, "PUBLIC", List.of(), List.of()));
        communities.join(slug, mod.user());
        communities.join(slug, member.user());
        communities.setRole(slug, owner.user(), mod.user().username(), CommunityRole.MODERATOR);
        PoolSummary pool = funding.createPool(slug, mod.user(), new CreatePoolRequest("Hosting", "Pays for the moderation bot hosting.", "USD",
                100_000L, null, Instant.now().plus(Duration.ofDays(60))));
        return new World(owner, mod, member, slug, pool);
    }

    /** A confirmed one-time contribution through (fake) Checkout. Returns the PaymentIntent id. */
    private String contribute(World w, long amount) {
        long pending = checkouts.contribute(w.pool().id(), w.member().user(), new ContributeRequest(amount, false, false)).checkout().pendingId();
        String session = stripe.sessionFor(pending), pi = "pi_test_connect_" + pending;
        stripe.payOnce(session, pi);
        assertThat(checkouts.confirm(w.member().user(), session).status()).isEqualTo("CONFIRMED");
        return pi;
    }

    private String accountOf(AuthUser u) {
        return stripe.accountByIdempotencyKey.get("howlers-connect-account-" + u.id() + "-test");
    }

    /** Onboards and finishes onboarding (as Stripe would), then the return page refreshes. */
    private String onboardEnabled(AuthUser u) {
        connect.startOnboarding(u);
        String acct = accountOf(u);
        stripe.enable(acct);
        assertThat(connect.refresh(u).account().status()).isEqualTo("ENABLED");
        return acct;
    }

    private ResultActions hook(String secret, String type, String objectJson, String account) throws Exception {
        String payload = StripeEvents.event("evt_test_c" + EVENTS.incrementAndGet(), type, false, objectJson);
        if (account != null) payload = payload.replaceFirst("\\{\"id\"", "{\"account\":\"" + account + "\",\"id\"");
        return hookRaw(secret, payload);
    }

    private ResultActions hookRaw(String secret, String payload) throws Exception {
        return mvc.perform(post("/api/v1/funding/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", StripeEvents.sign(secret, payload, Instant.now().getEpochSecond())));
    }

    private static String accountJson(String id, boolean submitted, boolean payouts, String transfers, int due) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < due; i++) items.append(i == 0 ? "" : ",").append("\"external_account\"");
        return """
                {"id":"%s","object":"account","type":"express","details_submitted":%s,"charges_enabled":false,"payouts_enabled":%s,\
                "capabilities":{"transfers":"%s"},"requirements":{"currently_due":[%s],"disabled_reason":null}}""".formatted(id, submitted,
                payouts, transfers, items);
    }

    private static String transferJson(Transfer t) {
        StringBuilder meta = new StringBuilder();
        t.metadata().forEach((k, v) -> meta.append(meta.isEmpty() ? "" : ",").append('"').append(k).append("\":\"").append(v).append('"'));
        return """
                {"id":"%s","object":"transfer","amount":%d,"amount_reversed":%d,"currency":"%s","destination":"%s","livemode":false,\
                "reversed":%s,"transfer_group":"%s","metadata":{%s}}""".formatted(t.id(), t.amountMinor(), t.amountReversed(), t.currency(),
                t.destination(), t.amountReversed() >= t.amountMinor(), t.transferGroup(), meta);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, U u) {
        return b.header("Authorization", "Bearer " + u.token());
    }

    private Allocation allocationOf(TransparencyReport r, AuthUser u) {
        return r.allocations().stream().filter(a -> a.moderator() != null && a.moderator().id() == u.id()).findFirst().orElseThrow();
    }

    private long payouts(long poolId, String status) {
        return jdbc.sql("SELECT count(*) FROM funding_payouts WHERE pool_id = ? AND status = ?").params(poolId, status).query(Long.class).single();
    }

    /** allocations = transferred + held, from the PAYOUT ledger rows, and the pool's own books still reconcile. */
    private static void assertPayoutsReconcile(TransparencyReport r) {
        PayoutReconciliation p = r.payouts();
        assertThat(p.applicable()).isTrue();
        long out = r.ledger().stream().filter(l -> l.entryType().equals("PAYOUT")).mapToLong(l -> l.amount().amountMinor()).sum();
        long back = r.ledger().stream().filter(l -> l.entryType().equals("PAYOUT_REVERSAL")).mapToLong(l -> l.amount().amountMinor()).sum();
        assertThat(p.transferred().amountMinor()).isEqualTo(out - back);
        assertThat(p.reversed().amountMinor()).isEqualTo(back);
        assertThat(p.allocated().amountMinor()).isEqualTo(p.transferred().amountMinor() + p.held().amountMinor())
                .isEqualTo(r.allocated().amountMinor());
        assertThat(p.reconciles()).isTrue();
        assertThat(r.reconciliation().reconciles()).isTrue();
        for (LedgerEntry l : r.ledger()) assertThat(l.amount().label()).isEqualTo(Money.TEST_LABEL);
    }

    // ------------------------------------------------------------------ onboarding

    @Test
    void onboardingMovesFromPendingToRequirementsDueToEnabled() throws Exception {
        World w = world();
        MyPayouts before = connect.my(w.mod().user());
        assertThat(before.connectEnabled()).isTrue();
        assertThat(before.mode()).isEqualTo("test");
        assertThat(before.eligible()).isTrue();
        assertThat(before.account().status()).isEqualTo("NOT_STARTED");

        OnboardingLink link = connect.startOnboarding(w.mod().user());
        assertThat(link.url()).startsWith("https://connect.stripe.com/");
        assertThat(stripe.linkReturnUrls.getLast()).isEqualTo("http://localhost:5173/funding/payouts?connect=return");
        String acct = accountOf(w.mod().user());
        MyPayouts pending = connect.my(w.mod().user());
        assertThat(pending.account().status()).isEqualTo("PENDING");
        assertThat(pending.account().accountHint()).isEqualTo("acct_…" + acct.substring(acct.length() - 4));
        // Only the id and flags are stored: there's no column for anything else.
        assertThat(jdbc.sql("SELECT account_id FROM funding_connected_accounts WHERE user_id = ?").param(w.mod().user().id())
                .query(String.class).single()).isEqualTo(acct);

        // Resuming reuses the same account and makes a fresh link.
        OnboardingLink again = connect.startOnboarding(w.mod().user());
        assertThat(again.url()).isNotEqualTo(link.url());
        assertThat(jdbc.sql("SELECT count(*) FROM funding_connected_accounts WHERE user_id = ?").param(w.mod().user().id())
                .query(Long.class).single()).isEqualTo(1);

        // Back from Stripe with something still due.
        stripe.submitWithRequirements(acct);
        MyPayouts due = connect.refresh(w.mod().user());
        assertThat(due.account().status()).isEqualTo("REQUIREMENTS_DUE");
        assertThat(due.account().requirementsDue()).isEqualTo(2);
        assertThat(due.account().message()).contains("Stripe needs more information (2 items)");

        // Stripe finishes verification and says so by webhook (signed with the connected-accounts endpoint's secret).
        hook(CONNECT_HOOK, "account.updated", accountJson(acct, true, true, "active", 0), acct).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));
        assertThat(connect.my(w.mod().user()).account().status()).isEqualTo("ENABLED");
        assertThat(connect.my(w.mod().user()).account().payoutsEnabled()).isTrue();

        // And back again if Stripe disables it.
        hook(HOOK, "account.updated", accountJson(acct, true, false, "inactive", 1), acct).andExpect(status().isOk());
        assertThat(connect.my(w.mod().user()).account().status()).isEqualTo("REQUIREMENTS_DUE");
    }

    @Test
    void onlyModeratorsOnboardThemselvesAndOnlyAdminsSeeTheStripePanel() throws Exception {
        World w = world();
        // Not signed in.
        mvc.perform(post("/api/v1/me/funding/connect/onboarding")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/funding/payouts")).andExpect(status().isUnauthorized());
        // A member who moderates nothing can't onboard.
        mvc.perform(as(post("/api/v1/me/funding/connect/onboarding"), w.member())).andExpect(status().isForbidden());
        assertThat(connect.my(w.member().user()).eligible()).isFalse();
        // The moderator onboards; the endpoint only ever acts on the caller's own account.
        mvc.perform(as(post("/api/v1/me/funding/connect/onboarding"), w.mod())).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://connect.stripe.com/")));
        mvc.perform(as(get("/api/v1/me/funding/payouts"), w.mod())).andExpect(status().isOk())
                .andExpect(jsonPath("$.account.status").value("PENDING"));
        mvc.perform(as(get("/api/v1/me/funding/payouts"), w.owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.account.status").value("NOT_STARTED"));

        // The Stripe status panel is admins only.
        mvc.perform(get("/api/v1/admin/funding/stripe")).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/admin/funding/stripe"), w.owner())).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/admin/funding/reviews"), w.mod())).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/admin/funding/payouts/run"), w.mod())).andExpect(status().isForbidden());
        U admin = user("ca" + System.nanoTime() % 1_000_000);
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = ?").param(admin.user().id()).update();
        String body = mvc.perform(as(get("/api/v1/admin/funding/stripe"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("test"))
                .andExpect(jsonPath("$.secretKeySet").value(true))
                .andExpect(jsonPath("$.webhookSecretSet").value(true))
                .andExpect(jsonPath("$.connectWebhookSecretSet").value(true))
                .andExpect(jsonPath("$.connectEnabled").value(true))
                .andExpect(jsonPath("$.webhookUrl").value("http://localhost:5173/api/v1/funding/webhooks/stripe"))
                .andExpect(jsonPath("$.connectEvents[0]").value("account.updated"))
                .andReturn().getResponse().getContentAsString();
        // No secret, not even part of one.
        assertThat(body).doesNotContain("whsec_").doesNotContain("sk_test").doesNotContain(HOOK).doesNotContain(CONNECT_HOOK);
    }

    // ------------------------------------------------------------------ payouts

    @Test
    void closingTransfersReadySharesAndHoldsTheRest() throws Exception {
        World w = world();
        String acct = onboardEnabled(w.mod().user());
        contribute(w, 1_001L);
        mvc.perform(as(post("/api/v1/funding/pools/" + w.pool().id() + "/close"), w.owner()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // EQUAL among owner and moderator (largest remainder): 501 + 500. The moderator's share went out at once.
        TransparencyReport r = funding.report(w.pool().id(), null);
        Allocation mod = allocationOf(r, w.mod().user()), owner = allocationOf(r, w.owner().user());
        assertThat(mod.payout().status()).isEqualTo("PAID");
        assertThat(mod.payout().label()).isEqualTo("Paid out (test mode)");
        assertThat(owner.payout().status()).isEqualTo("HELD");
        assertThat(owner.payout().label()).isEqualTo("Held: connect your account");
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);
        Transfer t = stripe.transfers.values().stream().filter(x -> acct.equals(x.destination())).findFirst().orElseThrow();
        assertThat(t.amountMinor()).isEqualTo(mod.amount().amountMinor());
        assertThat(t.currency()).isEqualTo("usd");
        assertThat(t.metadata()).containsKey(PayoutService.META_PAYOUT);
        assertThat(t.transferGroup()).startsWith("howlers-payout-");
        LedgerEntry payout = r.ledger().stream().filter(l -> l.entryType().equals("PAYOUT")).findFirst().orElseThrow();
        assertThat(payout.debitLabel()).isEqualTo("u/" + w.mod().user().username());
        assertThat(payout.creditLabel()).isEqualTo("u/" + w.mod().user().username() + "'s Stripe account (test mode)");
        assertThat(r.payouts().held().amountMinor()).isEqualTo(owner.amount().amountMinor());
        assertPayoutsReconcile(r);

        MyPayouts mine = connect.my(w.mod().user());
        assertThat(mine.payouts()).singleElement().satisfies(p -> {
            assertThat(p.label()).isEqualTo("Paid out (test mode)");
            assertThat(p.transferred().amountMinor()).isEqualTo(mod.amount().amountMinor());
        });
        MyPayouts owners = connect.my(w.owner().user());
        assertThat(owners.payouts()).singleElement().satisfies(p -> {
            assertThat(p.label()).isEqualTo("Held: connect your account");
            assertThat(p.reason()).isEqualTo("Connect your account to receive it.");
        });
        assertThat(owners.held()).singleElement().satisfies(m -> assertThat(m.amountMinor()).isEqualTo(owner.amount().amountMinor()));
        assertThat(funding.status().payouts()).contains("Stripe Connect in test mode");
    }

    @Test
    void heldSharesAreReleasedWhenOnboardingFinishesAndNeverSentTwice() throws Exception {
        World w = world();
        contribute(w, 2_000L);
        funding.close(w.pool().id(), w.owner().user(), null);
        assertThat(payouts(w.pool().id(), "HELD")).isEqualTo(2);
        assertThat(stripe.transfers.values()).noneMatch(t -> Long.toString(w.pool().id()).equals(t.metadata().get("howlers_pool_id")));
        // The job has nothing to send while nobody is connected.
        jobs.payoutsDue(Instant.now());
        assertThat(payouts(w.pool().id(), "HELD")).isEqualTo(2);
        assertPayoutsReconcile(funding.report(w.pool().id(), null));

        // The moderator onboards; Stripe says so by webhook; the held share is sent right after.
        connect.startOnboarding(w.mod().user());
        String acct = accountOf(w.mod().user());
        stripe.enable(acct);
        String payload = StripeEvents.event("evt_test_acct_" + acct, "account.updated", false, accountJson(acct, true, true, "active", 0));
        hookRaw(HOOK, payload).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);
        TransparencyReport r = funding.report(w.pool().id(), null);
        assertThat(allocationOf(r, w.mod().user()).payout().label()).isEqualTo("Paid out (test mode)");
        assertPayoutsReconcile(r);

        // Replaying the webhook, returning from onboarding, and rerunning the job send nothing more.
        hookRaw(HOOK, payload).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));
        connect.refresh(w.mod().user());
        jobs.payoutsDue(Instant.now());
        jobs.payoutsDue(Instant.now().plus(Duration.ofDays(2)));
        payouts.releaseForPool(w.pool().id());
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);
        assertThat(funding.report(w.pool().id(), null).ledger().stream().filter(l -> l.entryType().equals("PAYOUT")).count()).isEqualTo(1);
        // The transfer.created webhook for the same transfer is recognised.
        Transfer t = stripe.transfers.values().stream().filter(x -> acct.equals(x.destination())).findFirst().orElseThrow();
        hook(HOOK, "transfer.created", transferJson(t), null).andExpect(status().isOk());
        assertThat(lastOutcome()).isEqualTo("already recorded");
        assertPayoutsReconcile(funding.report(w.pool().id(), null));
    }

    @Test
    void refusedAndLostTransfersAreRetriedWithoutDoubleTransfers() throws Exception {
        World w = world();
        String acct = onboardEnabled(w.mod().user());
        contribute(w, 3_000L);
        // Stripe refuses the first transfer (a fresh sandbox has no available balance).
        stripe.refuseTransfers = 1;
        funding.close(w.pool().id(), w.owner().user(), null);
        long payoutId = jdbc.sql("SELECT id FROM funding_payouts WHERE pool_id = ? AND moderator_id = ?").params(w.pool().id(), w.mod().user().id())
                .query(Long.class).single();
        assertThat(payoutStatus(payoutId)).isEqualTo("FAILED");
        assertThat(connect.my(w.mod().user()).payouts().getFirst().label()).isEqualTo("Held: transfer failed, retrying");
        assertThat(stripe.transfersTo(acct)).isZero();
        // Not due yet: the job waits (backoff).
        jobs.payoutsDue(Instant.now());
        assertThat(stripe.transferCalls).filteredOn(k -> k.startsWith("howlers-payout-" + payoutId + "-")).hasSize(1);

        // When due, it retries with a new key (the refusal was definitive) and succeeds; but the answer is lost.
        stripe.loseNextTransferResponse = true;
        jobs.payoutsDue(Instant.now().plus(Duration.ofHours(2)));
        assertThat(payoutStatus(payoutId)).isEqualTo("FAILED");
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);
        assertThat(stripe.transferCalls).filteredOn(k -> k.startsWith("howlers-payout-" + payoutId + "-"))
                .containsExactly("howlers-payout-" + payoutId + "-0", "howlers-payout-" + payoutId + "-1");

        // The next run finds the transfer Stripe made (by its group) and records it; nothing is sent twice.
        jobs.payoutsDue(Instant.now().plus(Duration.ofHours(3)));
        assertThat(payoutStatus(payoutId)).isEqualTo("PAID");
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);
        jobs.payoutsDue(Instant.now().plus(Duration.ofDays(3)));
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);
        assertPayoutsReconcile(funding.report(w.pool().id(), null));
    }

    @Test
    void aTransferWhoseResponseWasLostIsRecordedFromTheWebhook() throws Exception {
        World w = world();
        String acct = onboardEnabled(w.mod().user());
        contribute(w, 1_000L);
        stripe.loseNextTransferResponse = true;
        funding.close(w.pool().id(), w.owner().user(), null);
        long payoutId = jdbc.sql("SELECT id FROM funding_payouts WHERE pool_id = ? AND moderator_id = ?").params(w.pool().id(), w.mod().user().id())
                .query(Long.class).single();
        assertThat(payoutStatus(payoutId)).isEqualTo("FAILED");
        Transfer t = stripe.transfers.values().stream().filter(x -> acct.equals(x.destination())).findFirst().orElseThrow();
        // A forged event naming the payout but another amount is not recorded.
        Transfer forged = new Transfer("tr_test_forged" + payoutId, t.amountMinor() + 1, 0, "usd", acct, t.transferGroup(), t.metadata());
        hook(HOOK, "transfer.created", transferJson(forged), null).andExpect(status().isOk());
        assertThat(payoutStatus(payoutId)).isEqualTo("FAILED");
        // The real one is.
        String payload = StripeEvents.event("evt_test_tr_" + t.id(), "transfer.created", false, transferJson(t));
        hookRaw(HOOK, payload).andExpect(status().isOk());
        assertThat(payoutStatus(payoutId)).isEqualTo("PAID");
        hookRaw(HOOK, payload).andExpect(jsonPath("$.status").value("duplicate"));
        jobs.payoutsDue(Instant.now().plus(Duration.ofHours(1)));
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);
        assertPayoutsReconcile(funding.report(w.pool().id(), null));
    }

    @Test
    void reversedTransfersAreBookedOnceAndHeldForReview() throws Exception {
        World w = world();
        String acct = onboardEnabled(w.mod().user());
        contribute(w, 4_000L);
        funding.close(w.pool().id(), w.owner().user(), null);
        Transfer t = stripe.transfers.values().stream().filter(x -> acct.equals(x.destination())).findFirst().orElseThrow();

        // A partial reversal, then the rest; each event is booked once, replays change nothing.
        Transfer half = stripe.reverse(t.id(), 500);
        String p1 = StripeEvents.event("evt_test_rev1_" + t.id(), "transfer.reversed", false, transferJson(half));
        hookRaw(HOOK, p1).andExpect(status().isOk());
        hookRaw(HOOK, p1).andExpect(jsonPath("$.status").value("duplicate"));
        // The same state under another event id (transfer.updated) isn't booked twice either.
        hook(HOOK, "transfer.updated", transferJson(half), null).andExpect(status().isOk());
        TransparencyReport r = funding.report(w.pool().id(), null);
        assertThat(r.payouts().reversed().amountMinor()).isEqualTo(500L);
        assertThat(allocationOf(r, w.mod().user()).payout().label()).isEqualTo("Paid out (test mode), partly reversed");
        assertPayoutsReconcile(r);

        Transfer full = stripe.reverse(t.id(), t.amountMinor());
        hook(HOOK, "transfer.reversed", transferJson(full), null).andExpect(status().isOk());
        r = funding.report(w.pool().id(), null);
        Allocation mod = allocationOf(r, w.mod().user());
        assertThat(mod.payout().status()).isEqualTo("REVERSED");
        assertThat(mod.payout().label()).isEqualTo("Reversed: held for review");
        assertThat(r.payouts().reversed().amountMinor()).isEqualTo(t.amountMinor());
        assertThat(r.ledger().stream().filter(l -> l.entryType().equals("PAYOUT_REVERSAL")).count()).isEqualTo(2);
        assertPayoutsReconcile(r);
        // A reversed payout is not re-sent automatically.
        jobs.payoutsDue(Instant.now().plus(Duration.ofDays(1)));
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM funding_review_flags WHERE kind = 'TRANSFER_REVERSED' AND pool_id = ?").param(w.pool().id())
                .query(Long.class).single()).isEqualTo(2);
        // Ledger rows stay append-only.
        assertThatThrownBy(() -> jdbc.sql("UPDATE funding_ledger SET amount_minor = 1 WHERE entry_type = 'PAYOUT_REVERSAL'").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void refundsAfterAPayoutAreFlaggedForAnAdminAndNeverClawedBack() throws Exception {
        World w = world();
        String acct = onboardEnabled(w.mod().user());
        String pi = contribute(w, 2_000L);
        funding.close(w.pool().id(), w.owner().user(), null);
        assertThat(stripe.transfersTo(acct)).isEqualTo(1);

        String charge = "ch_test_connect_" + w.pool().id();
        String refund = StripeEvents.event("evt_test_refund_" + charge, "charge.refunded", false, StripeEvents.charge(charge, pi, 2_000, 600, "usd"));
        hookRaw(HOOK, refund).andExpect(status().isOk());
        hookRaw(HOOK, refund).andExpect(jsonPath("$.status").value("duplicate"));
        // The transfer stands; the pool shows the shortfall; one flag waits for an admin.
        assertThat(stripe.transfers.get(stripe.transfers.values().stream().filter(x -> acct.equals(x.destination())).findFirst().orElseThrow().id())
                .amountReversed()).isZero();
        TransparencyReport r = funding.report(w.pool().id(), null);
        assertThat(r.reconciliation().shortfall().amountMinor()).isEqualTo(600L);
        assertPayoutsReconcile(r);

        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = ?").param(w.member().user().id()).update();
        AuthUser admin = new AuthUser(w.member().user().id(), w.member().user().username(), "ADMIN");
        String flags = mvc.perform(as(get("/api/v1/admin/funding/reviews"), w.member())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(flags).contains("REFUND_AFTER_PAYOUT").contains("Nothing is clawed back automatically");
        long flagId = jdbc.sql("SELECT id FROM funding_review_flags WHERE kind = 'REFUND_AFTER_PAYOUT' AND pool_id = ?").param(w.pool().id())
                .query(Long.class).single();
        mvc.perform(as(post("/api/v1/admin/funding/reviews/" + flagId + "/resolve"), w.mod()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Absorbed by the platform.\"}")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/admin/funding/reviews/" + flagId + "/resolve"), w.member()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Absorbed by the platform.\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionNote").value("Absorbed by the platform."));
        assertThat(connect.my(admin).payouts()).isEmpty();
        mvc.perform(as(get("/api/v1/admin/funding/stripe"), w.member())).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastWebhook.type").value("charge.refunded"));
    }

    @Test
    void connectDisabledErrorsAreReportedHonestly() {
        World w = world();
        stripe.connectNotActivated = true;
        try {
            assertThatThrownBy(() -> connect.startOnboarding(w.mod().user())).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY))
                    .hasMessageContaining("Stripe Connect isn't activated");
        } finally {
            stripe.connectNotActivated = false;
        }
        assertThat(connect.my(w.mod().user()).account().status()).isEqualTo("NOT_STARTED");
    }

    private String payoutStatus(long payoutId) {
        return jdbc.sql("SELECT status FROM funding_payouts WHERE id = ?").param(payoutId).query(String.class).single();
    }

    private String lastOutcome() {
        return jdbc.sql("SELECT outcome FROM funding_webhook_events WHERE provider = 'stripe' ORDER BY received_at DESC, event_id DESC LIMIT 1")
                .query(String.class).single();
    }
}

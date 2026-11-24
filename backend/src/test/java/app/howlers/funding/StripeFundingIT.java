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
import app.howlers.funding.FundingDtos.CheckoutStatus;
import app.howlers.funding.FundingDtos.ContributeRequest;
import app.howlers.funding.FundingDtos.ContributeResult;
import app.howlers.funding.FundingDtos.CreatePoolRequest;
import app.howlers.funding.FundingDtos.LedgerEntry;
import app.howlers.funding.FundingDtos.PoolSummary;
import app.howlers.funding.FundingDtos.SubscriptionDto;
import app.howlers.funding.FundingDtos.TransparencyReport;
import app.howlers.funding.payment.PaymentProvider.CheckoutRequest;
import app.howlers.funding.payment.StripeGateway;
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
import app.howlers.support.TestIds;

/**
 * Funding with FUNDING_PROVIDER=stripe (test mode) against a fake {@link StripeGateway}: hosted checkout, confirmation
 * by return and by webhook, recurring invoices, refunds as reversing rows, and idempotency everywhere. No network.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "howlers.funding.provider=stripe",
        "howlers.funding.stripe.secret-key=sk_test_fake_key_for_the_test_suite",
        "howlers.funding.stripe.webhook-secret=" + StripeFundingIT.HOOK,
        "howlers.app.public-url=http://localhost:5173/"})
@Import(StripeFundingIT.FakeStripe.class)
class StripeFundingIT extends IntegrationTest {
    static final String HOOK = "whsec_integration_test_0123456789";
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
    @Autowired FundingJobs jobs;
    @Autowired FakeStripeGateway stripe;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@s.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private record World(AuthUser owner, AuthUser mod, AuthUser member, AuthUser other, String slug, PoolSummary pool) {}

    private World world() {
        String s = TestIds.suffix();
        AuthUser owner = user("so" + s), mod = user("sm" + s), member = user("sc" + s), other = user("sx" + s);
        String slug = "stripe" + s;
        communities.create(owner, new CreateCommunityRequest(slug, "Stripe funding test", "", null, "PUBLIC", List.of(), List.of()));
        for (AuthUser u : List.of(mod, member, other)) communities.join(slug, u);
        communities.setRole(slug, owner, mod.username(), CommunityRole.MODERATOR);
        PoolSummary pool = funding.createPool(slug, mod, new CreatePoolRequest("Hosting", "Pays for the moderation bot hosting.", "USD", 100_000L,
                null, Instant.now().plus(Duration.ofDays(60))));
        return new World(owner, mod, member, other, slug, pool);
    }

    private ResultActions hook(String type, String objectJson) throws Exception {
        String payload = StripeEvents.event("evt_test_" + EVENTS.incrementAndGet(), type, false, objectJson);
        return hookRaw(payload);
    }

    private ResultActions hookRaw(String payload) throws Exception {
        return mvc.perform(post("/api/v1/funding/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", StripeEvents.sign(HOOK, payload, Instant.now().getEpochSecond())));
    }

    private long contributions(long poolId) {
        return jdbc.sql("SELECT count(*) FROM funding_contributions WHERE pool_id = ?").param(poolId).query(Long.class).single();
    }

    private static void assertReconciles(TransparencyReport r) {
        long in = r.ledger().stream().filter(l -> l.entryType().equals("CONTRIBUTION")).mapToLong(l -> l.amount().amountMinor()).sum();
        long allocations = r.ledger().stream().filter(l -> l.entryType().equals("ALLOCATION")).mapToLong(l -> l.amount().amountMinor()).sum();
        long refunds = r.ledger().stream().filter(l -> l.entryType().equals("REFUND")).mapToLong(l -> l.amount().amountMinor()).sum();
        assertThat(r.reconciliation().contributionsTotal().amountMinor()).isEqualTo(in);
        assertThat(r.reconciliation().refundsTotal().amountMinor()).isEqualTo(refunds).isEqualTo(r.refunded().amountMinor());
        assertThat(r.reconciliation().ledgerOut().amountMinor()).isEqualTo(allocations + refunds);
        assertThat(r.raised().amountMinor()).isEqualTo(in - refunds).isEqualTo(r.pool().raised().amountMinor());
        assertThat(r.reconciliation().reconciles()).isTrue();
        for (LedgerEntry l : r.ledger()) {
            assertThat(l.amount().mode()).isEqualTo("test");
            assertThat(l.amount().label()).isEqualTo(Money.TEST_LABEL);
        }
    }

    @Test
    void statusSaysStripeTestModeAndDemoChargesAreNeverMade() {
        var s = funding.status();
        assertThat(s.provider()).isEqualTo("stripe");
        assertThat(s.mode()).isEqualTo("test");
        assertThat(s.label()).isEqualTo("TEST MODE: no real money");
        assertThat(s.checkout()).isEqualTo("hosted");
        assertThat(s.payouts()).contains("not paid out");
        World w = world();
        assertThat(w.pool().mode()).isEqualTo("test");
        assertThat(w.pool().goal().label()).isEqualTo(Money.TEST_LABEL);
        // The instant path refuses outright rather than inventing a charge.
        assertThatThrownBy(() -> funding.contribute(w.pool().id(), w.member(), new ContributeRequest(500L, false, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oneTimeCheckoutIsPendingUntilConfirmedOnReturnAndThenIdempotent() throws Exception {
        World w = world();
        ContributeResult start = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(1_250L, false, false));
        assertThat(start.contribution()).isNull();
        assertThat(start.checkout().url()).startsWith("https://checkout.stripe.com/");
        assertThat(start.checkout().mode()).isEqualTo("test");
        String sessionId = stripe.sessionFor(start.checkout().pendingId());
        CheckoutRequest req = stripe.requests.get(sessionId);
        assertThat(req.recurring()).isFalse();
        assertThat(req.amountMinor()).isEqualTo(1_250L);
        assertThat(req.currency()).isEqualTo("USD");
        assertThat(req.clientReferenceId()).isEqualTo(Long.toString(start.checkout().pendingId()));
        assertThat(req.metadata()).containsEntry("howlers_pool_id", Long.toString(w.pool().id()))
                .containsEntry("howlers_contributor_id", Long.toString(w.member().id()))
                .containsEntry("howlers_contribution_id", Long.toString(start.checkout().pendingId()))
                .containsEntry("howlers_anonymous", "true");
        assertThat(req.successUrl()).isEqualTo("http://localhost:5173/funding/pools/" + w.pool().id() + "?checkout=success&session_id={CHECKOUT_SESSION_ID}");
        assertThat(req.cancelUrl()).isEqualTo("http://localhost:5173/funding/pools/" + w.pool().id() + "?checkout=cancelled&pending="
                + start.checkout().pendingId());
        assertThat(req.idempotencyKey()).isEqualTo("howlers-pending-contribution-" + start.checkout().pendingId());

        // Not paid yet: nothing counts, polling says PENDING.
        assertThat(funding.report(w.pool().id(), null).raised().amountMinor()).isZero();
        assertThat(checkouts.confirm(w.member(), sessionId).status()).isEqualTo("PENDING");
        // Someone else can't confirm (or even see) it.
        assertThatThrownBy(() -> checkouts.confirm(w.other(), sessionId)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));

        stripe.payOnce(sessionId, "pi_test_once_" + sessionId);
        CheckoutStatus done = checkouts.confirm(w.member(), sessionId);
        assertThat(done.status()).isEqualTo("CONFIRMED");
        assertThat(done.contribution().amount().amountMinor()).isEqualTo(1_250L);
        assertThat(done.contribution().amount().label()).isEqualTo(Money.TEST_LABEL);
        assertThat(done.contribution().chargeId()).isEqualTo("pi_test_once_" + sessionId);
        assertThat(done.contribution().provider()).isEqualTo("stripe");

        // Polling again, and the webhook for the same session, change nothing.
        assertThat(checkouts.confirm(w.member(), sessionId).status()).isEqualTo("CONFIRMED");
        hook("checkout.session.completed", StripeEvents.session(sessionId, "payment", "complete", "paid", start.checkout().pendingId(), 1_250,
                "usd", "pi_test_once_" + sessionId, null, null)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
        assertThat(contributions(w.pool().id())).isEqualTo(1);
        TransparencyReport r = funding.report(w.pool().id(), null);
        assertThat(r.raised().amountMinor()).isEqualTo(1_250L);
        assertThat(r.namedContributors()).isEmpty();
        assertReconciles(r);
    }

    @Test
    void webhookConfirmsWithoutTheReturnAndReplaysAreIgnored() throws Exception {
        World w = world();
        long pending = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(700L, false, true)).checkout().pendingId();
        String sessionId = stripe.sessionFor(pending);
        String payload = StripeEvents.event("evt_test_replay_" + pending, "checkout.session.completed", false,
                StripeEvents.session(sessionId, "payment", "complete", "paid", pending, 700, "usd", "pi_test_hook_" + pending, null, null));

        // Unsigned, wrongly signed, tampered and stale requests are refused and change nothing.
        mvc.perform(post("/api/v1/funding/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/funding/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", StripeEvents.sign("whsec_wrong_secret_000000000", payload, Instant.now().getEpochSecond())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/funding/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload.replace("700", "70000"))
                .header("Stripe-Signature", StripeEvents.sign(HOOK, payload, Instant.now().getEpochSecond())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/funding/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", StripeEvents.sign(HOOK, payload, Instant.now().getEpochSecond() - 3600)))
                .andExpect(status().isBadRequest());
        // The demo endpoint doesn't exist while Stripe is the provider.
        mvc.perform(post("/api/v1/funding/webhooks/demo").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", StripeEvents.sign(HOOK, payload, Instant.now().getEpochSecond())))
                .andExpect(status().isNotFound());
        assertThat(contributions(w.pool().id())).isZero();

        hookRaw(payload).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
        hookRaw(payload).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));
        assertThat(contributions(w.pool().id())).isEqualTo(1);
        assertThat(checkouts.get(w.member(), pending).status()).isEqualTo("CONFIRMED");
        // The contributor opted in to be named.
        assertThat(funding.report(w.pool().id(), null).namedContributors()).extracting(u -> u.username()).containsExactly(w.member().username());
        // The return confirmation after the webhook answers from the database without double-counting.
        assertThat(checkouts.confirm(w.member(), sessionId).status()).isEqualTo("CONFIRMED");
        assertThat(contributions(w.pool().id())).isEqualTo(1);
    }

    @Test
    void monthlyCheckoutRecordsEachPaidInvoiceOnceAndStripeDrivesTheCycles() throws Exception {
        World w = world();
        long pending = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(300L, true, false)).checkout().pendingId();
        String sessionId = stripe.sessionFor(pending);
        assertThat(stripe.requests.get(sessionId).recurring()).isTrue();
        String sub = "sub_test_" + pending, first = "in_test_first_" + pending, second = "in_test_second_" + pending;

        // Stripe may deliver the first invoice before the session event: the subscription is found through its metadata.
        hook("invoice.paid", StripeEvents.invoice(first, sub, 300, "usd", pending)).andExpect(status().isOk());
        assertThat(checkouts.get(w.member(), pending).status()).isEqualTo("CONFIRMED");
        stripe.paySubscription(sessionId, sub, first);
        hook("checkout.session.completed", StripeEvents.session(sessionId, "subscription", "complete", "paid", pending, 300, "usd", null, sub, first))
                .andExpect(status().isOk());
        assertThat(checkouts.confirm(w.member(), sessionId).subscription().cyclesCharged()).isEqualTo(1);
        assertThat(contributions(w.pool().id())).isEqualTo(1);

        // The demo job never charges a Stripe subscription.
        assertThat(jobs.chargeDue(Instant.now().plus(Duration.ofDays(40)))).isZero();
        SubscriptionDto s = funding.my(w.member()).subscriptions().getFirst();
        assertThat(s.billedBy()).isEqualTo("provider");
        assertThat(s.nextChargeAt()).isNull();

        // A failed renewal is shown, then the retried payment clears it and records cycle 2, once.
        hook("invoice.payment_failed", StripeEvents.invoice(second, sub, 0, "usd", null)).andExpect(status().isOk());
        assertThat(funding.my(w.member()).subscriptions().getFirst().paymentFailedAt()).isNotNull();
        hook("invoice.paid", StripeEvents.invoice(second, sub, 300, "usd", null)).andExpect(status().isOk());
        hook("invoice.paid", StripeEvents.invoice(second, sub, 300, "usd", null)).andExpect(status().isOk()); // new event id, same invoice
        SubscriptionDto after = funding.my(w.member()).subscriptions().getFirst();
        assertThat(after.cyclesCharged()).isEqualTo(2);
        assertThat(after.paymentFailedAt()).isNull();
        assertThat(contributions(w.pool().id())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT cycle FROM funding_contributions WHERE pool_id = ? ORDER BY cycle").param(w.pool().id()).query(Integer.class).list())
                .containsExactly(1, 2);

        // Cancelling calls Stripe; Stripe's own deletion event afterwards is a no-op.
        SubscriptionDto cancelled = funding.cancelSubscription(after.id(), w.member());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledBy()).isEqualTo("USER");
        assertThat(stripe.cancelled).contains(sub);
        hook("customer.subscription.deleted", StripeEvents.subscription(sub)).andExpect(status().isOk());
        assertThat(funding.my(w.member()).subscriptions().getFirst().cancelledBy()).isEqualTo("USER");
        assertReconciles(funding.report(w.pool().id(), null));
    }

    @Test
    void subscriptionDeletedInStripeIsCancelledByProvider() throws Exception {
        World w = world();
        long pending = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(500L, true, false)).checkout().pendingId();
        String sessionId = stripe.sessionFor(pending), sub = "sub_test_del_" + pending;
        stripe.paySubscription(sessionId, sub, "in_test_del_" + pending);
        assertThat(checkouts.confirm(w.member(), sessionId).status()).isEqualTo("CONFIRMED");
        hook("customer.subscription.deleted", StripeEvents.subscription(sub)).andExpect(status().isOk());
        SubscriptionDto s = funding.my(w.member()).subscriptions().getFirst();
        assertThat(s.status()).isEqualTo("CANCELLED");
        assertThat(s.cancelledBy()).isEqualTo("PROVIDER");
    }

    @Test
    void refundsReverseWithNewLedgerRowsAndNeverEditOldOnes() throws Exception {
        World w = world();
        long pending = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(1_000L, false, false)).checkout().pendingId();
        String sessionId = stripe.sessionFor(pending), pi = "pi_test_refund_" + pending, ch = "ch_test_refund_" + pending;
        stripe.payOnce(sessionId, pi);
        long cid = checkouts.confirm(w.member(), sessionId).contribution().id();

        // A partial refund, delivered twice (Stripe retry with a new event id): one reversal of 400.
        hook("charge.refunded", StripeEvents.charge(ch, pi, 1_000, 400, "usd")).andExpect(status().isOk());
        hook("charge.refunded", StripeEvents.charge(ch, pi, 1_000, 400, "usd")).andExpect(status().isOk());
        TransparencyReport r = funding.report(w.pool().id(), null);
        assertThat(r.raised().amountMinor()).isEqualTo(600L);
        assertThat(r.refunded().amountMinor()).isEqualTo(400L);
        assertThat(r.refundCount()).isEqualTo(1);
        assertThat(r.ledger()).extracting(LedgerEntry::entryType).containsExactly("CONTRIBUTION", "REFUND");
        LedgerEntry refundRow = r.ledger().get(1);
        assertThat(refundRow.debitAccount()).isEqualTo("pool:" + w.pool().id());
        assertThat(refundRow.creditAccount()).isEqualTo("contributors");
        assertReconciles(r);

        // The rest is refunded later: cumulative 1000 adds a second reversal of 600; the original row is untouched.
        hook("charge.refunded", StripeEvents.charge(ch, pi, 1_000, 1_000, "usd")).andExpect(status().isOk());
        r = funding.report(w.pool().id(), null);
        assertThat(r.raised().amountMinor()).isZero();
        assertThat(r.ledger()).extracting(l -> l.amount().amountMinor()).containsExactly(1_000L, 400L, 600L);
        assertThat(funding.my(w.member()).contributions().getFirst().refunded().amountMinor()).isEqualTo(1_000L);
        assertThat(jdbc.sql("SELECT amount_minor FROM funding_contributions WHERE id = ?").param(cid).query(Long.class).single()).isEqualTo(1_000L);
        assertReconciles(r);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM funding_refunds WHERE contribution_id = ?").param(cid).update())
                .hasMessageContaining("append-only");
        // A refund Howlers knows nothing about is acknowledged and ignored.
        hook("charge.refunded", StripeEvents.charge("ch_test_unknown", "pi_test_unknown", 500, 500, "usd")).andExpect(status().isOk());
    }

    @Test
    void refundOfARecurringInvoiceIsFoundThroughStripeAndAfterCloseIsAShortfall() throws Exception {
        World w = world();
        long pending = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(800L, true, false)).checkout().pendingId();
        String sessionId = stripe.sessionFor(pending), sub = "sub_test_close_" + pending, inv = "in_test_close_" + pending;
        stripe.paySubscription(sessionId, sub, inv);
        checkouts.confirm(w.member(), sessionId);

        // Closing cancels the Stripe subscription and allocates the 800 (as obligations; nothing is paid out).
        TransparencyReport closed = funding.close(w.pool().id(), w.owner(), null);
        assertThat(closed.pool().status()).isEqualTo("CLOSED");
        assertThat(closed.allocated().amountMinor()).isEqualTo(800L);
        assertThat(stripe.cancelled).contains(sub);
        assertThat(funding.my(w.member()).subscriptions().getFirst().status()).isEqualTo("ENDED");

        // The charge names only its PaymentIntent (2025+ API); Howlers asks Stripe which invoice it paid.
        String pi = "pi_test_inv_" + pending;
        stripe.invoiceByPaymentIntent.put(pi, inv);
        hook("charge.refunded", StripeEvents.charge("ch_test_inv_" + pending, pi, 800, 300, "usd")).andExpect(status().isOk());
        TransparencyReport r = funding.report(w.pool().id(), null);
        assertThat(r.refunded().amountMinor()).isEqualTo(300L);
        assertThat(r.reconciliation().shortfall().amountMinor()).isEqualTo(300L);
        assertThat(r.reconciliation().poolBalance().amountMinor()).isEqualTo(-300L);
        assertReconciles(r);

        // A renewal that still arrives for the closed pool is not added to it.
        hook("invoice.paid", StripeEvents.invoice("in_test_late_" + pending, sub, 800, "usd", null)).andExpect(status().isOk());
        assertThat(contributions(w.pool().id())).isEqualTo(1);
    }

    @Test
    void cancelledAndExpiredCheckoutsRecordNothing() throws Exception {
        World w = world();
        long a = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(500L, false, false)).checkout().pendingId();
        CheckoutStatus c = checkouts.cancel(w.member(), a);
        assertThat(c.status()).isEqualTo("CANCELLED");
        assertThat(stripe.sessions.get(stripe.sessionFor(a)).status()).isEqualTo("expired");
        assertThatThrownBy(() -> checkouts.cancel(w.other(), a)).isInstanceOf(ApiException.class);

        long b = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(500L, false, false)).checkout().pendingId();
        String sb = stripe.sessionFor(b);
        hook("checkout.session.expired", StripeEvents.session(sb, "payment", "expired", "unpaid", b, 500, "usd", null, null, null))
                .andExpect(status().isOk());
        assertThat(checkouts.get(w.member(), b).status()).isEqualTo("EXPIRED");
        assertThat(contributions(w.pool().id())).isZero();

        // A paid session whose amount doesn't match what Howlers asked for is never recorded.
        long d = checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(500L, false, false)).checkout().pendingId();
        String sd = stripe.sessionFor(d);
        hook("checkout.session.completed", StripeEvents.session(sd, "payment", "complete", "paid", d, 1, "usd", "pi_test_tiny", null, null))
                .andExpect(status().isOk());
        assertThat(checkouts.get(w.member(), d).status()).isEqualTo("FAILED");
        assertThat(contributions(w.pool().id())).isZero();

        // A Stripe outage while starting leaves no pending row behind.
        long before = jdbc.sql("SELECT count(*) FROM funding_pending_contributions WHERE pool_id = ?").param(w.pool().id()).query(Long.class).single();
        stripe.failNext = true;
        assertThatThrownBy(() -> checkouts.contribute(w.pool().id(), w.member(), new ContributeRequest(500L, false, false)))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(jdbc.sql("SELECT count(*) FROM funding_pending_contributions WHERE pool_id = ?").param(w.pool().id()).query(Long.class).single())
                .isEqualTo(before);
    }

    @Test
    void httpEndpointsRequireAuthAndReportsShowTestLabels() throws Exception {
        World w = world();
        mvc.perform(post("/api/v1/funding/checkouts/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"cs_x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/funding/checkouts/1/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/funding/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("test")).andExpect(jsonPath("$.label").value(Money.TEST_LABEL))
                .andExpect(jsonPath("$.checkout").value("hosted"));
        mvc.perform(get("/api/v1/funding/pools/" + w.pool().id() + "/report")).andExpect(status().isOk())
                .andExpect(jsonPath("$.raised.label").value(Money.TEST_LABEL)).andExpect(jsonPath("$.raised.mode").value("test"))
                .andExpect(jsonPath("$.reconciliation.reconciles").value(true));
    }
}

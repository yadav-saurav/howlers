package app.howlers.funding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.funding.payment.FundingRuntime;
import app.howlers.funding.payment.PaymentProvider.WebhookVerificationException;
import app.howlers.funding.payment.PaymentProviderConfig;
import app.howlers.funding.payment.StripePaymentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Stripe provider selection, the live-key refusal and webhook signature verification. No network, no Spring. */
class StripeProviderTest {
    private static final String HOOK = "whsec_unit_test_0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeStripeGateway gateway = new FakeStripeGateway();
    private final StripePaymentProvider test = new StripePaymentProvider(gateway, "test", HOOK, 300, mapper, clock);

    private static FundingProperties stripe(String key, String hook, Boolean live, String ack) {
        return new FundingProperties(true, "stripe", null, null, null, live, ack, new FundingProperties.Stripe(key, hook));
    }

    private FundingRuntime select(FundingProperties p) {
        return PaymentProviderConfig.select(p, mapper, clock, k -> gateway);
    }

    @Test
    void testKeysSelectTestModeWithItsOwnLabel() {
        FundingRuntime r = select(stripe("sk_test_abc123", HOOK, null, null));
        assertThat(r.provider().id()).isEqualTo("stripe");
        assertThat(r.provider().mode()).isEqualTo("test");
        assertThat(r.provider().demo()).isTrue();
        assertThat(r.provider().label()).isEqualTo("TEST MODE: no real money");
        assertThat(r.provider().hostedCheckout()).isTrue();
        assertThat(r.provider().signatureHeader()).isEqualTo("Stripe-Signature");
        assertThat(select(stripe("rk_test_abc123", null, false, null)).provider().mode()).isEqualTo("test");
        // No webhook secret is allowed in test mode (return-confirmation still works), but every webhook is then refused.
        var noHooks = (StripePaymentProvider) select(stripe("sk_test_abc123", "", null, null)).provider();
        assertThat(noHooks.webhooksConfigured()).isFalse();
        assertThatThrownBy(() -> noHooks.verifyWebhook("{}", "t=1,v1=00")).hasMessageContaining("STRIPE_WEBHOOK_SECRET");
    }

    @Test
    void liveKeysAreRefusedUnlessLiveModeIsExplicitlyAcknowledged() {
        assertThatThrownBy(() -> select(stripe("sk_live_abc123", HOOK, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FUNDING_LIVE_MODE=true").hasMessageContaining("FUNDING_COMPLIANCE_ACK");
        assertThatThrownBy(() -> select(stripe("rk_live_abc123", HOOK, null, null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> select(stripe("sk_live_abc123", HOOK, true, null))).hasMessageContaining("FUNDING_COMPLIANCE_ACK");
        assertThatThrownBy(() -> select(stripe("sk_live_abc123", HOOK, true, "  "))).hasMessageContaining("FUNDING_COMPLIANCE_ACK");
        assertThatThrownBy(() -> select(stripe("sk_live_abc123", HOOK, false, "signed off"))).hasMessageContaining("FUNDING_LIVE_MODE");
        // Live needs webhooks: refunds and renewals only arrive that way.
        assertThatThrownBy(() -> select(stripe("sk_live_abc123", "", true, "Legal review 2026-11-01, KYC via Stripe")))
                .hasMessageContaining("STRIPE_WEBHOOK_SECRET");
        FundingRuntime live = select(stripe("sk_live_abc123", HOOK, true, "Legal review 2026-11-01, KYC via Stripe"));
        assertThat(live.provider().mode()).isEqualTo("live");
        assertThat(live.provider().demo()).isFalse();
        assertThat(live.provider().label()).isNull();
        // Live mode with a test key is a mistake too.
        assertThatThrownBy(() -> select(stripe("sk_test_abc123", HOOK, true, "x"))).hasMessageContaining("test key");
        // Anything that isn't a secret key is refused, and the message never echoes the key.
        assertThatThrownBy(() -> select(stripe("pk_test_abc123", HOOK, null, null))).hasMessageContaining("publishable")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("pk_test_abc123"));
        assertThatThrownBy(() -> select(stripe("sk_tset_typo", HOOK, null, null))).hasMessageNotContaining("sk_tset_typo");
        assertThatThrownBy(() -> select(stripe(null, HOOK, null, null))).hasMessageContaining("STRIPE_SECRET_KEY");
    }

    @Test
    void propertiesNeverPrintKeys() {
        FundingProperties p = stripe("sk_test_supersecret", "whsec_supersecret", null, null);
        assertThat(p.toString()).doesNotContain("supersecret").contains("***");
        assertThat(p.stripe().toString()).doesNotContain("supersecret");
    }

    @Test
    void signedEventsVerifyAndEverythingElseIsRejected() {
        String payload = StripeEvents.event("evt_1", "checkout.session.completed", false,
                StripeEvents.session("cs_test_1", "payment", "complete", "paid", 7, 500, "usd", "pi_1", null, null));
        String header = StripeEvents.sign(HOOK, payload, NOW.getEpochSecond());
        var event = test.verifyWebhook(payload, header);
        assertThat(event.id()).isEqualTo("evt_1");
        assertThat(event.type()).isEqualTo("checkout.session.completed");
        var session = StripePaymentProvider.session(event.data());
        assertThat(session.id()).isEqualTo("cs_test_1");
        assertThat(session.paid()).isTrue();
        assertThat(session.paymentRef()).isEqualTo("pi_1");
        assertThat(session.metadata()).containsEntry("howlers_contribution_id", "7");

        assertThatThrownBy(() -> test.verifyWebhook(payload, null)).isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> test.verifyWebhook(payload, "")).isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> test.verifyWebhook(payload, "garbage")).isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> test.verifyWebhook(payload.replace("500", "50000"), header)).isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> test.verifyWebhook(payload, StripeEvents.sign("whsec_another_secret_000000", payload, NOW.getEpochSecond())))
                .isInstanceOf(WebhookVerificationException.class);
        // Stale and future timestamps are outside the tolerance, so a captured request can't be replayed later.
        assertThatThrownBy(() -> test.verifyWebhook(payload, StripeEvents.sign(HOOK, payload, NOW.getEpochSecond() - 301)))
                .isInstanceOf(WebhookVerificationException.class);
        // Several v1 signatures (secret rotation): one valid one is enough.
        String rotated = StripeEvents.sign("whsec_old_secret_0000000000", payload, NOW.getEpochSecond()) + ",v1="
                + header.substring(header.indexOf("v1=") + 3);
        assertThat(test.verifyWebhook(payload, rotated).id()).isEqualTo("evt_1");

        // A live-mode event can't be replayed into a test-mode server (and vice versa).
        String livePayload = payload.replace("\"livemode\":false,\"pending", "\"livemode\":true,\"pending");
        assertThatThrownBy(() -> test.verifyWebhook(livePayload, StripeEvents.sign(HOOK, livePayload, NOW.getEpochSecond())))
                .hasMessageContaining("Live-mode event");
        // Signed but not an event.
        String notEvent = "[1,2,3]";
        assertThatThrownBy(() -> test.verifyWebhook(notEvent, StripeEvents.sign(HOOK, notEvent, NOW.getEpochSecond())))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void invoiceAndChargeFieldsAreReadFromOldAndNewApiShapes() throws Exception {
        Map<String, Object> modern = mapper.readValue(StripeEvents.invoice("in_1", "sub_1", 300, "usd", 9L), Map.class);
        assertThat(StripePaymentProvider.invoiceSubscription(modern)).isEqualTo("sub_1");
        assertThat(StripePaymentProvider.invoiceSubscriptionMetadata(modern)).containsEntry("howlers_contribution_id", "9");
        Map<String, Object> legacy = mapper.readValue("{\"id\":\"in_2\",\"subscription\":\"sub_2\",\"payment_intent\":\"pi_2\"}", Map.class);
        assertThat(StripePaymentProvider.invoiceSubscription(legacy)).isEqualTo("sub_2");
        assertThat(StripePaymentProvider.invoicePaymentIntent(legacy)).isEqualTo("pi_2");
        Map<String, Object> expanded = mapper.readValue("{\"subscription\":{\"id\":\"sub_3\",\"object\":\"subscription\"}}", Map.class);
        assertThat(StripePaymentProvider.invoiceSubscription(expanded)).isEqualTo("sub_3");
    }
}

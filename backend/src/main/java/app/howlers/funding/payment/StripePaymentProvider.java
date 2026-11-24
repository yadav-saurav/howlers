package app.howlers.funding.payment;

import app.howlers.funding.Money;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Stripe through hosted Checkout (D-034). Card data never reaches Howlers: the browser is sent to Stripe's page, and a
 * contribution only counts once Stripe confirms it. Stripe bills recurring cycles itself; Howlers records each paid invoice.
 *
 * <p>Webhooks are verified with the SDK's {@link Webhook#constructEvent} (HMAC-SHA256 over {@code t.payload}, constant-time
 * compare, timestamp tolerance), then parsed as plain JSON so Howlers never depends on the SDK's typed model for the
 * account's API version. An event from the other mode (a test event sent to a live server, or the reverse) is refused.
 */
public class StripePaymentProvider implements PaymentProvider {
    public static final String ID = "stripe";
    public static final String SIGNATURE_HEADER = "Stripe-Signature";

    private final StripeGateway gateway;
    private final String mode;
    private final String webhookSecret;
    private final String connectWebhookSecret;
    private final long toleranceSeconds;
    private final ObjectMapper mapper;
    private final Clock clock;

    public StripePaymentProvider(StripeGateway gateway, String mode, String webhookSecret, long toleranceSeconds, ObjectMapper mapper, Clock clock) {
        this(gateway, mode, webhookSecret, null, toleranceSeconds, mapper, clock);
    }

    /**
     * @param connectWebhookSecret the signing secret of a separate dashboard endpoint for connected-account events
     *                             (optional; a signature valid for either secret is accepted)
     */
    public StripePaymentProvider(StripeGateway gateway, String mode, String webhookSecret, String connectWebhookSecret, long toleranceSeconds,
                                 ObjectMapper mapper, Clock clock) {
        if (!Money.MODE_TEST.equals(mode) && !Money.MODE_LIVE.equals(mode)) throw new IllegalArgumentException("mode must be test or live");
        this.gateway = gateway;
        this.mode = mode;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.strip();
        this.connectWebhookSecret = connectWebhookSecret == null ? "" : connectWebhookSecret.strip();
        this.toleranceSeconds = toleranceSeconds;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * The mode a secret key belongs to, from its documented prefix: {@code sk_test_}/{@code rk_test_} or
     * {@code sk_live_}/{@code rk_live_}. Anything else (a publishable key, a typo) is refused.
     */
    public static String modeOfKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("FUNDING_PROVIDER=stripe needs STRIPE_SECRET_KEY (a test key, sk_test_...)");
        String k = key.strip();
        if (k.startsWith("sk_test_") || k.startsWith("rk_test_")) return Money.MODE_TEST;
        if (k.startsWith("sk_live_") || k.startsWith("rk_live_")) return Money.MODE_LIVE;
        if (k.startsWith("pk_")) throw new IllegalStateException("STRIPE_SECRET_KEY holds a publishable key (pk_...); use the secret key (sk_test_...)");
        throw new IllegalStateException("STRIPE_SECRET_KEY doesn't look like a Stripe secret key (expected sk_test_... or sk_live_...)");
    }

    public boolean webhooksConfigured() {
        return !webhookSecret.isEmpty();
    }

    public boolean connectWebhooksConfigured() {
        return !connectWebhookSecret.isEmpty();
    }

    public StripeGateway gateway() {
        return gateway;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String mode() {
        return mode;
    }

    @Override
    public String notice() {
        return Money.MODE_TEST.equals(mode)
                ? "Stripe test mode: payments go through Stripe's hosted checkout with test cards only, and no real money moves."
                : "Payments go through Stripe's hosted checkout; Howlers never sees card details.";
    }

    @Override
    public boolean hostedCheckout() {
        return true;
    }

    @Override
    public String signatureHeader() {
        return SIGNATURE_HEADER;
    }

    @Override
    public CheckoutSession startCheckout(CheckoutRequest request) {
        if (request.amountMinor() <= 0) throw new IllegalArgumentException("amount must be positive");
        return gateway.createCheckoutSession(request);
    }

    @Override
    public CheckoutSession retrieveCheckout(String sessionId) {
        return gateway.retrieveCheckoutSession(sessionId);
    }

    @Override
    public CheckoutSession expireCheckout(String sessionId) {
        return gateway.expireCheckoutSession(sessionId);
    }

    @Override
    public String invoiceForPayment(String paymentRef) {
        return gateway.invoiceForPaymentIntent(paymentRef);
    }

    @Override
    public void cancel(String subscriptionRef) {
        gateway.cancelSubscription(subscriptionRef);
    }

    @Override
    public WebhookEvent verifyWebhook(String payload, String signatureHeader) {
        if (webhookSecret.isEmpty() && connectWebhookSecret.isEmpty()) {
            throw new WebhookVerificationException("Stripe webhooks aren't configured on this server (STRIPE_WEBHOOK_SECRET)");
        }
        if (payload == null || signatureHeader == null || signatureHeader.isBlank()) throw new WebhookVerificationException("Missing signature");
        // The platform endpoint and an optional connected-accounts endpoint have their own secrets; either may have signed it.
        String failure = null;
        boolean verified = false;
        for (String secret : new String[] {webhookSecret, connectWebhookSecret}) {
            if (secret.isEmpty() || verified) continue;
            try {
                Webhook.constructEvent(payload, signatureHeader, secret, toleranceSeconds, clock);
                verified = true;
            } catch (SignatureVerificationException e) {
                failure = e.getMessage();
            } catch (RuntimeException e) {
                throw new WebhookVerificationException("Payload is not a Stripe event");
            }
        }
        if (!verified) throw new WebhookVerificationException(failure == null ? "Invalid signature" : failure);
        Map<String, Object> body;
        try {
            body = mapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new WebhookVerificationException("Payload is not JSON");
        }
        Object id = body.get("id"), type = body.get("type");
        if (!(id instanceof String sid) || sid.isBlank() || sid.length() > 100 || !(type instanceof String st) || st.isBlank()) {
            throw new WebhookVerificationException("Event needs an id and a type");
        }
        boolean live = Boolean.TRUE.equals(body.get("livemode"));
        if (live != Money.MODE_LIVE.equals(mode)) {
            throw new WebhookVerificationException(live ? "Live-mode event sent to a test-mode server" : "Test-mode event sent to a live server");
        }
        Map<String, Object> object = map(map(body.get("data")).get("object"));
        return new WebhookEvent(sid, st, object, str(body.get("account")));
    }

    // ------------------------------------------------------------------ reading event objects (plain JSON maps)

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static String str(Object o) {
        if (o instanceof String s && !s.isBlank()) return s;
        // Expanded objects ({"id": "...", ...}) stand in for their id.
        if (o instanceof Map<?, ?> m && m.get("id") instanceof String s && !s.isBlank()) return s;
        return null;
    }

    public static Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    public static Map<String, String> metadata(Object o) {
        Map<String, String> out = new HashMap<>();
        map(o).forEach((k, v) -> {
            if (v != null) out.put(k, v.toString());
        });
        return out;
    }

    /** A Checkout Session from a webhook's {@code data.object}. */
    public static CheckoutSession session(Map<String, Object> o) {
        return new CheckoutSession(str(o.get("id")), str(o.get("url")), str(o.get("status")), str(o.get("payment_status")),
                "subscription".equals(o.get("mode")), str(o.get("client_reference_id")), metadata(o.get("metadata")),
                num(o.get("amount_total")), str(o.get("currency")), str(o.get("payment_intent")), str(o.get("subscription")),
                str(o.get("invoice")), o.get("livemode") instanceof Boolean b ? b : null);
    }

    /** The subscription an invoice belongs to: {@code parent.subscription_details.subscription} (2025+ API) or {@code subscription}. */
    public static String invoiceSubscription(Map<String, Object> invoice) {
        String s = str(map(map(invoice.get("parent")).get("subscription_details")).get("subscription"));
        return s != null ? s : str(invoice.get("subscription"));
    }

    /** The subscription metadata copied onto an invoice (where Howlers' pending contribution id travels). */
    public static Map<String, String> invoiceSubscriptionMetadata(Map<String, Object> invoice) {
        Map<String, String> m = metadata(map(map(invoice.get("parent")).get("subscription_details")).get("metadata"));
        if (m.isEmpty()) m = metadata(map(invoice.get("subscription_details")).get("metadata"));
        return m;
    }

    /** The PaymentIntent that paid an invoice, when the event carries it (older API versions). */
    public static String invoicePaymentIntent(Map<String, Object> invoice) {
        String pi = str(invoice.get("payment_intent"));
        if (pi != null) return pi;
        Object payments = map(invoice.get("payments")).get("data");
        if (payments instanceof Iterable<?> it) {
            for (Object p : it) {
                String v = str(map(map(p).get("payment")).get("payment_intent"));
                if (v != null) return v;
            }
        }
        return null;
    }
}

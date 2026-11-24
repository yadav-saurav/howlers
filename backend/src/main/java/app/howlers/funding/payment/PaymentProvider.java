package app.howlers.funding.payment;

import app.howlers.funding.Money;
import java.util.Map;

/**
 * The only way funding code talks to a payment processor. Two kinds exist (docs/DECISIONS.md D-022, D-034):
 * <ul>
 *   <li><b>instant</b> ({@link DemoPaymentProvider}): Howlers asks for a charge and gets the result back at once;</li>
 *   <li><b>hosted checkout</b> ({@link StripePaymentProvider}): the browser is sent to the provider's own payment page, card
 *       data never reaches Howlers, and a contribution only counts once the provider confirms it (webhook or a
 *       server-side check on return). The provider also drives recurring cycles itself.</li>
 * </ul>
 */
public interface PaymentProvider {

    /** Stable id stored on every pool, charge and subscription (e.g. "demo", "stripe"). */
    String id();

    /** {@link Money#MODE_DEMO}, {@link Money#MODE_TEST} or {@link Money#MODE_LIVE}. Stored on every money row. */
    String mode();

    /** True when charges move no real money (demo and test mode). */
    default boolean demo() {
        return !Money.MODE_LIVE.equals(mode());
    }

    /** The label clients must show next to every amount from this provider, or null for live money. */
    default String label() {
        return Money.labelFor(mode());
    }

    /** One sentence for the page banner explaining what this provider does with money. */
    String notice();

    /** True when contributions go through a hosted checkout page (see {@link #startCheckout}). */
    default boolean hostedCheckout() {
        return false;
    }

    /** The request header carrying the webhook signature. */
    String signatureHeader();

    /** Charges once (instant providers only). {@code idempotencyKey} must make retries return the same result. */
    default Charge createCharge(ChargeRequest request) {
        throw new UnsupportedOperationException(id() + " does not create charges directly");
    }

    /** Sets up a monthly subscription (instant providers only). Cycles are charged through {@link #createCharge}. */
    default Subscription createSubscription(SubscriptionRequest request) {
        throw new UnsupportedOperationException(id() + " does not create subscriptions directly");
    }

    /** Starts a hosted checkout and returns where to send the browser (hosted providers only). */
    default CheckoutSession startCheckout(CheckoutRequest request) {
        throw new UnsupportedOperationException(id() + " has no hosted checkout");
    }

    /** Fetches a checkout session from the provider, server-side, to confirm it without trusting the browser. */
    default CheckoutSession retrieveCheckout(String sessionId) {
        throw new UnsupportedOperationException(id() + " has no hosted checkout");
    }

    /** Expires an open checkout session so it can no longer be paid. Must be safe if it already completed or expired. */
    default CheckoutSession expireCheckout(String sessionId) {
        throw new UnsupportedOperationException(id() + " has no hosted checkout");
    }

    /** The invoice a provider payment belongs to, when events don't say (hosted providers only). */
    default String invoiceForPayment(String paymentRef) {
        return null;
    }

    /** Cancels a subscription at the provider. Must be safe to call twice. */
    void cancel(String subscriptionRef);

    /**
     * Verifies a webhook's signature and freshness and parses it.
     *
     * @throws WebhookVerificationException when the signature is missing, malformed, wrong or too old
     */
    WebhookEvent verifyWebhook(String payload, String signatureHeader);

    record ChargeRequest(long amountMinor, String currency, String description, String idempotencyKey) {}

    record Charge(String chargeId, boolean succeeded, boolean demo) {}

    record SubscriptionRequest(long amountMinor, String currency, String description, String idempotencyKey) {}

    record Subscription(String subscriptionRef, boolean demo) {}

    /**
     * A hosted checkout for one pending contribution.
     *
     * @param recurring  false: a one-time payment; true: a monthly subscription with the price created inline
     * @param metadata   copied to the session (and the subscription or payment) so every later event names the contribution
     */
    record CheckoutRequest(boolean recurring, long amountMinor, String currency, String productName, String description,
                           String successUrl, String cancelUrl, String clientReferenceId, Map<String, String> metadata,
                           long expiresAtEpochSeconds, String idempotencyKey) {}

    /**
     * What Howlers needs from a provider checkout session, whether it came from an API call or a webhook.
     *
     * @param status         open | complete | expired
     * @param paymentStatus  paid | unpaid | no_payment_required
     * @param paymentRef     the one-time payment's id (Stripe PaymentIntent), when there is one
     * @param subscriptionRef the subscription created by a recurring checkout
     * @param invoiceRef     the first invoice of a recurring checkout
     */
    record CheckoutSession(String id, String url, String status, String paymentStatus, boolean recurring, String clientReferenceId,
                           Map<String, String> metadata, Long amountTotal, String currency, String paymentRef,
                           String subscriptionRef, String invoiceRef, Boolean livemode) {
        public boolean paid() {
            return "complete".equals(status) && ("paid".equals(paymentStatus) || "no_payment_required".equals(paymentStatus));
        }
    }

    /**
     * A verified webhook. {@code data} is the event's object (for Stripe, {@code data.object}). {@code account} is the
     * connected account an event happened on (Stripe Connect events such as {@code payout.paid}), or null.
     */
    record WebhookEvent(String id, String type, Map<String, Object> data, String account) {
        public WebhookEvent(String id, String type, Map<String, Object> data) {
            this(id, type, data, null);
        }
    }

    class WebhookVerificationException extends RuntimeException {
        public WebhookVerificationException(String message) {
            super(message);
        }
    }
}

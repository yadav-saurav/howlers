package app.howlers.funding.payment;

import app.howlers.funding.payment.PaymentProvider.CheckoutRequest;
import app.howlers.funding.payment.PaymentProvider.CheckoutSession;
import java.util.List;
import java.util.Map;

/**
 * The few Stripe API calls Howlers makes. {@link SdkStripeGateway} implements it with the official SDK; tests use a fake,
 * so the suite never calls Stripe.
 */
public interface StripeGateway {

    /** Creates a hosted Checkout Session (payment or subscription mode, the price inline via {@code price_data}). */
    CheckoutSession createCheckoutSession(CheckoutRequest request);

    CheckoutSession retrieveCheckoutSession(String sessionId);

    /** Expires the session if it's still open; otherwise returns it unchanged. */
    CheckoutSession expireCheckoutSession(String sessionId);

    /** Cancels the subscription now. A subscription that is already cancelled is not an error. */
    void cancelSubscription(String subscriptionId);

    /** The invoice paid by this PaymentIntent, or null when it isn't an invoice payment. */
    String invoiceForPaymentIntent(String paymentIntentId);

    // ------------------------------------------------------------------ Connect (D-046, D-047)

    /**
     * Creates an Express connected account with the {@code transfers} capability requested. Only the country and Howlers'
     * own metadata are sent; Stripe's hosted onboarding collects identity and bank details, which never reach Howlers.
     */
    default ConnectedAccount createExpressAccount(String country, Map<String, String> metadata, String idempotencyKey) {
        throw new UnsupportedOperationException("This gateway does not support Connect");
    }

    default ConnectedAccount retrieveAccount(String accountId) {
        throw new UnsupportedOperationException("This gateway does not support Connect");
    }

    /** Deletes a connected account (test-mode clean-up only). */
    default void deleteAccount(String accountId) {
        throw new UnsupportedOperationException("This gateway does not support Connect");
    }

    /** A single-use onboarding link (Stripe-hosted) that returns to {@code returnUrl}, or to {@code refreshUrl} if it expired. */
    default AccountLink createAccountLink(String accountId, String refreshUrl, String returnUrl) {
        throw new UnsupportedOperationException("This gateway does not support Connect");
    }

    /** Moves money from the platform balance to a connected account. The idempotency key makes a retry return the same transfer. */
    default Transfer createTransfer(TransferRequest request) {
        throw new UnsupportedOperationException("This gateway does not support Connect");
    }

    default Transfer retrieveTransfer(String transferId) {
        throw new UnsupportedOperationException("This gateway does not support Connect");
    }

    /** Transfers already made with this transfer group (to find one whose response was lost). */
    default List<Transfer> transfersInGroup(String transferGroup) {
        throw new UnsupportedOperationException("This gateway does not support Connect");
    }

    /**
     * The status flags Howlers keeps about a connected account. {@code transfersCapability} is Stripe's capability status
     * (active, inactive, pending, unrequested); {@code requirementsDue} counts {@code requirements.currently_due}.
     */
    record ConnectedAccount(String id, boolean detailsSubmitted, boolean chargesEnabled, boolean payoutsEnabled, String transfersCapability,
                            int requirementsDue, String disabledReason) {}

    record AccountLink(String url, long expiresAt) {}

    record TransferRequest(long amountMinor, String currency, String destination, String transferGroup, String description,
                           Map<String, String> metadata, String idempotencyKey) {}

    record Transfer(String id, long amountMinor, long amountReversed, String currency, String destination, String transferGroup,
                    Map<String, String> metadata) {}

    /**
     * A Stripe call failed. The message is Stripe's (never contains the key) plus the request id. {@code outcomeUnknown}
     * is true when Stripe may have acted without Howlers seeing the answer (a network error or a 5xx), so the call must be
     * retried with the same idempotency key, never a new one. {@code code} is Stripe's error code, when it sent one.
     */
    class StripeGatewayException extends RuntimeException {
        private final boolean outcomeUnknown;
        private final String code;

        public StripeGatewayException(String message, Throwable cause) {
            this(message, cause, false, null);
        }

        public StripeGatewayException(String message, Throwable cause, boolean outcomeUnknown, String code) {
            super(message, cause);
            this.outcomeUnknown = outcomeUnknown;
            this.code = code;
        }

        public boolean outcomeUnknown() {
            return outcomeUnknown;
        }

        public String code() {
            return code;
        }
    }
}

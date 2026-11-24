package app.howlers.funding.payment;

import app.howlers.funding.payment.PaymentProvider.CheckoutRequest;
import app.howlers.funding.payment.PaymentProvider.CheckoutSession;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.InvoicePayment;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoicePaymentListParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.ProductData;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.Recurring;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Account;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@link StripeGateway} over the official SDK. The secret key lives only inside the {@link StripeClient}. */
public class SdkStripeGateway implements StripeGateway {
    private final StripeClient client;

    public SdkStripeGateway(String secretKey) {
        this.client = StripeClient.builder().setApiKey(secretKey).setConnectTimeout(10_000).setReadTimeout(20_000)
                .setMaxNetworkRetries(2).build();
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutRequest r) {
        PriceData.Builder price = PriceData.builder()
                .setCurrency(r.currency().toLowerCase(Locale.ROOT))
                .setUnitAmount(r.amountMinor())
                .setProductData(ProductData.builder().setName(r.productName()).setDescription(r.description()).build());
        if (r.recurring()) price.setRecurring(Recurring.builder().setInterval(Recurring.Interval.MONTH).build());
        SessionCreateParams.Builder b = SessionCreateParams.builder()
                .setMode(r.recurring() ? SessionCreateParams.Mode.SUBSCRIPTION : SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(r.successUrl())
                .setCancelUrl(r.cancelUrl())
                .setClientReferenceId(r.clientReferenceId())
                .setExpiresAt(r.expiresAtEpochSeconds())
                .addLineItem(LineItem.builder().setQuantity(1L).setPriceData(price.build()).build());
        r.metadata().forEach(b::putMetadata);
        if (r.recurring()) {
            SessionCreateParams.SubscriptionData.Builder sub = SessionCreateParams.SubscriptionData.builder().setDescription(r.description());
            r.metadata().forEach(sub::putMetadata);
            b.setSubscriptionData(sub.build());
        } else {
            SessionCreateParams.PaymentIntentData.Builder pi = SessionCreateParams.PaymentIntentData.builder().setDescription(r.description());
            r.metadata().forEach(pi::putMetadata);
            b.setPaymentIntentData(pi.build());
        }
        try {
            return map(client.v1().checkout().sessions().create(b.build(),
                    RequestOptions.builder().setIdempotencyKey(r.idempotencyKey()).build()));
        } catch (StripeException e) {
            throw wrap("create a Checkout Session", e);
        }
    }

    @Override
    public CheckoutSession retrieveCheckoutSession(String sessionId) {
        try {
            return map(client.v1().checkout().sessions().retrieve(sessionId));
        } catch (StripeException e) {
            throw wrap("retrieve the Checkout Session", e);
        }
    }

    @Override
    public CheckoutSession expireCheckoutSession(String sessionId) {
        try {
            Session s = client.v1().checkout().sessions().retrieve(sessionId);
            if (!"open".equals(s.getStatus())) return map(s);
            return map(client.v1().checkout().sessions().expire(sessionId));
        } catch (StripeException e) {
            throw wrap("expire the Checkout Session", e);
        }
    }

    @Override
    public void cancelSubscription(String subscriptionId) {
        try {
            Subscription s = client.v1().subscriptions().retrieve(subscriptionId);
            if ("canceled".equals(s.getStatus()) || "incomplete_expired".equals(s.getStatus())) return;
            client.v1().subscriptions().cancel(subscriptionId);
        } catch (StripeException e) {
            throw wrap("cancel the subscription", e);
        }
    }

    @Override
    public String invoiceForPaymentIntent(String paymentIntentId) {
        try {
            var page = client.v1().invoicePayments().list(InvoicePaymentListParams.builder()
                    .setPayment(InvoicePaymentListParams.Payment.builder()
                            .setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT)
                            .setPaymentIntent(paymentIntentId).build())
                    .setLimit(1L).build());
            return page.getData().stream().map(InvoicePayment::getInvoice).findFirst().orElse(null);
        } catch (StripeException e) {
            throw wrap("look up the invoice for a payment", e);
        }
    }

    static CheckoutSession map(Session s) {
        Map<String, String> meta = s.getMetadata() == null ? Map.of() : Map.copyOf(s.getMetadata());
        return new CheckoutSession(s.getId(), s.getUrl(), s.getStatus(), s.getPaymentStatus(), "subscription".equals(s.getMode()),
                s.getClientReferenceId(), meta, s.getAmountTotal(), s.getCurrency(), s.getPaymentIntent(), s.getSubscription(),
                s.getInvoice(), s.getLivemode());
    }

    // ------------------------------------------------------------------ Connect

    @Override
    public ConnectedAccount createExpressAccount(String country, Map<String, String> metadata, String idempotencyKey) {
        AccountCreateParams.Builder b = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setCountry(country)
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build()).build());
        metadata.forEach(b::putMetadata);
        try {
            return map(client.v1().accounts().create(b.build(), RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()));
        } catch (StripeException e) {
            throw wrap("create a connected account", e);
        }
    }

    @Override
    public ConnectedAccount retrieveAccount(String accountId) {
        try {
            return map(client.v1().accounts().retrieve(accountId));
        } catch (StripeException e) {
            throw wrap("retrieve the connected account", e);
        }
    }

    @Override
    public void deleteAccount(String accountId) {
        try {
            client.v1().accounts().delete(accountId);
        } catch (StripeException e) {
            throw wrap("delete the connected account", e);
        }
    }

    @Override
    public AccountLink createAccountLink(String accountId, String refreshUrl, String returnUrl) {
        try {
            var link = client.v1().accountLinks().create(AccountLinkCreateParams.builder().setAccount(accountId)
                    .setRefreshUrl(refreshUrl).setReturnUrl(returnUrl).setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING).build());
            return new AccountLink(link.getUrl(), link.getExpiresAt() == null ? 0 : link.getExpiresAt());
        } catch (StripeException e) {
            throw wrap("create an onboarding link", e);
        }
    }

    @Override
    public Transfer createTransfer(TransferRequest r) {
        TransferCreateParams.Builder b = TransferCreateParams.builder().setAmount(r.amountMinor())
                .setCurrency(r.currency().toLowerCase(Locale.ROOT)).setDestination(r.destination()).setTransferGroup(r.transferGroup())
                .setDescription(r.description());
        r.metadata().forEach(b::putMetadata);
        try {
            return map(client.v1().transfers().create(b.build(), RequestOptions.builder().setIdempotencyKey(r.idempotencyKey()).build()));
        } catch (StripeException e) {
            throw wrap("create the transfer", e);
        }
    }

    @Override
    public Transfer retrieveTransfer(String transferId) {
        try {
            return map(client.v1().transfers().retrieve(transferId));
        } catch (StripeException e) {
            throw wrap("retrieve the transfer", e);
        }
    }

    @Override
    public List<Transfer> transfersInGroup(String transferGroup) {
        try {
            return client.v1().transfers().list(TransferListParams.builder().setTransferGroup(transferGroup).setLimit(10L).build())
                    .getData().stream().map(SdkStripeGateway::map).toList();
        } catch (StripeException e) {
            throw wrap("list transfers", e);
        }
    }

    static ConnectedAccount map(Account a) {
        String transfers = a.getCapabilities() == null ? null : a.getCapabilities().getTransfers();
        int due = a.getRequirements() == null || a.getRequirements().getCurrentlyDue() == null ? 0 : a.getRequirements().getCurrentlyDue().size();
        String disabled = a.getRequirements() == null ? null : a.getRequirements().getDisabledReason();
        return new ConnectedAccount(a.getId(), Boolean.TRUE.equals(a.getDetailsSubmitted()), Boolean.TRUE.equals(a.getChargesEnabled()),
                Boolean.TRUE.equals(a.getPayoutsEnabled()), transfers == null ? "unrequested" : transfers, due, disabled);
    }

    static Transfer map(com.stripe.model.Transfer t) {
        return new Transfer(t.getId(), t.getAmount() == null ? 0 : t.getAmount(), t.getAmountReversed() == null ? 0 : t.getAmountReversed(),
                t.getCurrency(), t.getDestination(), t.getTransferGroup(), t.getMetadata() == null ? Map.of() : Map.copyOf(t.getMetadata()));
    }

    private static StripeGatewayException wrap(String what, StripeException e) {
        String id = e.getRequestId() == null ? "" : " (request " + e.getRequestId() + ")";
        // A network error or a server-side (5xx) error: Stripe may have acted, so a retry must reuse the idempotency key.
        boolean unknown = e instanceof ApiConnectionException || e instanceof com.stripe.exception.ApiException
                || (e.getStatusCode() != null && e.getStatusCode() >= 500);
        return new StripeGatewayException("Stripe couldn't " + what + ": " + e.getMessage() + id, e, unknown, e.getCode());
    }
}

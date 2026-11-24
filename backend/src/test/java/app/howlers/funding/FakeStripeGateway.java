package app.howlers.funding;

import app.howlers.funding.payment.PaymentProvider.CheckoutRequest;
import app.howlers.funding.payment.PaymentProvider.CheckoutSession;
import app.howlers.funding.payment.StripeGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory stand-in for Stripe: no network. Tests move sessions to "paid" the way Checkout would. */
public class FakeStripeGateway implements StripeGateway {
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    public final Map<String, CheckoutSession> sessions = new ConcurrentHashMap<>();
    public final Map<String, CheckoutRequest> requests = new ConcurrentHashMap<>();
    public final Map<String, String> invoiceByPaymentIntent = new ConcurrentHashMap<>();
    public final List<String> cancelled = new ArrayList<>();
    public final List<String> idempotencyKeys = new ArrayList<>();
    public volatile boolean failNext;

    @Override
    public CheckoutSession createCheckoutSession(CheckoutRequest r) {
        if (failNext) {
            failNext = false;
            throw new StripeGatewayException("Stripe couldn't create a Checkout Session: simulated outage", null);
        }
        String id = "cs_test_fake" + SEQ.incrementAndGet();
        CheckoutSession s = new CheckoutSession(id, "https://checkout.stripe.com/c/pay/" + id, "open", "unpaid", r.recurring(),
                r.clientReferenceId(), Map.copyOf(r.metadata()), r.amountMinor(), r.currency().toLowerCase(), null, null, null, false);
        sessions.put(id, s);
        requests.put(id, r);
        idempotencyKeys.add(r.idempotencyKey());
        return s;
    }

    @Override
    public CheckoutSession retrieveCheckoutSession(String sessionId) {
        CheckoutSession s = sessions.get(sessionId);
        if (s == null) throw new StripeGatewayException("No such checkout session", null);
        return s;
    }

    @Override
    public CheckoutSession expireCheckoutSession(String sessionId) {
        CheckoutSession s = retrieveCheckoutSession(sessionId);
        if (!"open".equals(s.status())) return s;
        CheckoutSession expired = with(s, "expired", "unpaid", null, null, null);
        sessions.put(sessionId, expired);
        return expired;
    }

    @Override
    public synchronized void cancelSubscription(String subscriptionId) {
        cancelled.add(subscriptionId);
    }

    @Override
    public String invoiceForPaymentIntent(String paymentIntentId) {
        return invoiceByPaymentIntent.get(paymentIntentId);
    }

    /** What Checkout does when the contributor pays a one-time session. */
    public CheckoutSession payOnce(String sessionId, String paymentIntent) {
        CheckoutSession s = with(retrieveCheckoutSession(sessionId), "complete", "paid", paymentIntent, null, null);
        sessions.put(sessionId, s);
        return s;
    }

    /** What Checkout does when the contributor starts a subscription: a subscription and its first, paid invoice. */
    public CheckoutSession paySubscription(String sessionId, String subscription, String invoice) {
        CheckoutSession s = with(retrieveCheckoutSession(sessionId), "complete", "paid", null, subscription, invoice);
        sessions.put(sessionId, s);
        return s;
    }

    // ------------------------------------------------------------------ Connect

    public final Map<String, ConnectedAccount> accounts = new ConcurrentHashMap<>();
    public final Map<String, String> accountByIdempotencyKey = new ConcurrentHashMap<>();
    public final List<AccountLink> links = new ArrayList<>();
    public final List<String> linkReturnUrls = new ArrayList<>();
    public final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    public final Map<String, String> transferByIdempotencyKey = new ConcurrentHashMap<>();
    /** Every createTransfer call, including ones answered from the idempotency cache. */
    public final List<String> transferCalls = new ArrayList<>();
    /** The next N transfer calls are refused by Stripe (e.g. balance_insufficient). */
    public volatile int refuseTransfers;
    /** The next transfer is made, but the response is lost (network error after Stripe acted). */
    public volatile boolean loseNextTransferResponse;
    /** Account creation fails the way Stripe answers a platform that hasn't activated Connect. */
    public volatile boolean connectNotActivated;

    @Override
    public synchronized ConnectedAccount createExpressAccount(String country, Map<String, String> metadata, String idempotencyKey) {
        if (connectNotActivated) {
            throw new StripeGatewayException("Stripe couldn't create a connected account: You can only create new accounts if you've signed up for "
                    + "Connect, which you can learn how to do at https://stripe.com/docs/connect.", null, false, null);
        }
        String existing = accountByIdempotencyKey.get(idempotencyKey);
        if (existing != null) return accounts.get(existing);
        String id = "acct_test" + SEQ.incrementAndGet();
        ConnectedAccount a = new ConnectedAccount(id, false, false, false, "inactive", 5, "requirements.past_due");
        accounts.put(id, a);
        accountByIdempotencyKey.put(idempotencyKey, id);
        return a;
    }

    @Override
    public ConnectedAccount retrieveAccount(String accountId) {
        ConnectedAccount a = accounts.get(accountId);
        if (a == null) throw new StripeGatewayException("No such account", null);
        return a;
    }

    @Override
    public void deleteAccount(String accountId) {
        accounts.remove(accountId);
    }

    @Override
    public synchronized AccountLink createAccountLink(String accountId, String refreshUrl, String returnUrl) {
        retrieveAccount(accountId);
        AccountLink l = new AccountLink("https://connect.stripe.com/setup/e/" + accountId + "/" + SEQ.incrementAndGet(), 1_900_000_000L);
        links.add(l);
        linkReturnUrls.add(returnUrl);
        return l;
    }

    /** What Stripe onboarding does: details submitted, but something still due. */
    public ConnectedAccount submitWithRequirements(String accountId) {
        ConnectedAccount a = new ConnectedAccount(accountId, true, false, false, "pending", 2, null);
        accounts.put(accountId, a);
        return a;
    }

    /** Onboarding finished and verified: transfers active, payouts enabled. */
    public ConnectedAccount enable(String accountId) {
        ConnectedAccount a = new ConnectedAccount(accountId, true, true, true, "active", 0, null);
        accounts.put(accountId, a);
        return a;
    }

    @Override
    public synchronized Transfer createTransfer(TransferRequest r) {
        transferCalls.add(r.idempotencyKey());
        String cached = transferByIdempotencyKey.get(r.idempotencyKey());
        if (cached != null) return transfers.get(cached);
        if (refuseTransfers > 0) {
            refuseTransfers--;
            throw new StripeGatewayException("Stripe couldn't create the transfer: You have insufficient available funds in your Stripe account.",
                    null, false, "balance_insufficient");
        }
        if (!accounts.containsKey(r.destination())) throw new StripeGatewayException("No such destination", null, false, "resource_missing");
        String id = "tr_test" + SEQ.incrementAndGet();
        Transfer t = new Transfer(id, r.amountMinor(), 0, r.currency().toLowerCase(), r.destination(), r.transferGroup(), Map.copyOf(r.metadata()));
        transfers.put(id, t);
        transferByIdempotencyKey.put(r.idempotencyKey(), id);
        if (loseNextTransferResponse) {
            loseNextTransferResponse = false;
            throw new StripeGatewayException("Stripe couldn't create the transfer: connection reset", null, true, null);
        }
        return t;
    }

    @Override
    public Transfer retrieveTransfer(String transferId) {
        return transfers.get(transferId);
    }

    @Override
    public List<Transfer> transfersInGroup(String transferGroup) {
        return transfers.values().stream().filter(t -> transferGroup.equals(t.transferGroup())).toList();
    }

    /** A reversal made in the Stripe dashboard. Returns the updated transfer. */
    public Transfer reverse(String transferId, long amount) {
        Transfer t = transfers.get(transferId);
        Transfer r = new Transfer(t.id(), t.amountMinor(), Math.min(t.amountMinor(), t.amountReversed() + amount), t.currency(), t.destination(),
                t.transferGroup(), t.metadata());
        transfers.put(transferId, r);
        return r;
    }

    public long transfersTo(String accountId) {
        return transfers.values().stream().filter(t -> accountId.equals(t.destination())).count();
    }

    public String sessionFor(long pendingId) {
        return requests.entrySet().stream().filter(e -> e.getValue().clientReferenceId().equals(Long.toString(pendingId)))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static CheckoutSession with(CheckoutSession s, String status, String paymentStatus, String pi, String sub, String invoice) {
        return new CheckoutSession(s.id(), s.url(), status, paymentStatus, s.recurring(), s.clientReferenceId(), s.metadata(), s.amountTotal(),
                s.currency(), pi, sub, invoice, s.livemode());
    }
}

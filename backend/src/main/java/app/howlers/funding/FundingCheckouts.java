package app.howlers.funding;

import app.howlers.common.config.AppProperties;
import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.funding.FundingDtos.CheckoutStart;
import app.howlers.funding.FundingDtos.CheckoutStatus;
import app.howlers.funding.FundingDtos.ContributeRequest;
import app.howlers.funding.FundingDtos.ContributeResult;
import app.howlers.funding.FundingDtos.WebhookResult;
import app.howlers.funding.FundingService.PoolRow;
import app.howlers.funding.payment.FundingRuntime;
import app.howlers.funding.payment.PaymentProvider;
import app.howlers.funding.payment.PaymentProvider.CheckoutRequest;
import app.howlers.funding.payment.PaymentProvider.CheckoutSession;
import app.howlers.funding.payment.PaymentProvider.WebhookEvent;
import app.howlers.funding.payment.PaymentProvider.WebhookVerificationException;
import app.howlers.funding.payment.StripePaymentProvider;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hosted-checkout contributions (Stripe Checkout, D-034, D-035).
 *
 * <p>Contributing creates a PENDING row and a Checkout Session; the browser goes to Stripe. Nothing counts until the
 * payment is confirmed, which happens in one of two ways, both idempotent and both funnelled through {@link #apply}:
 * the {@code checkout.session.completed} webhook, or the contributor's return to the success URL, where the server
 * retrieves the session from Stripe itself (so local development works without {@code stripe listen}). Stripe bills
 * monthly cycles; each paid invoice becomes one contribution, exactly once. Refunds become reversing ledger rows.
 *
 * <p>Instant providers (demo) are passed through to {@link FundingService} unchanged.
 */
@Service
public class FundingCheckouts {
    private static final Logger log = LoggerFactory.getLogger(FundingCheckouts.class);
    static final String META_POOL = "howlers_pool_id";
    static final String META_CONTRIBUTOR = "howlers_contributor_id";
    static final String META_PENDING = "howlers_contribution_id";
    static final String META_ANONYMOUS = "howlers_anonymous";
    static final String META_KIND = "howlers_kind";
    private static final int MAX_PENDING_PER_HOUR = 10;

    private final FundingService funding;
    private final FundingRuntime runtime;
    private final JdbcClient jdbc;
    private final RateLimiter limiter;
    private final String publicUrl;
    private final PayoutService payouts;
    private final ConnectService connect;

    public FundingCheckouts(FundingService funding, FundingRuntime runtime, JdbcClient jdbc, RateLimiter limiter, AppProperties props,
                            PayoutService payouts, ConnectService connect) {
        this.payouts = payouts;
        this.connect = connect;
        this.funding = funding;
        this.runtime = runtime;
        this.jdbc = jdbc;
        this.limiter = limiter;
        String url = props.app() == null || props.app().publicUrl() == null ? "http://localhost:5173" : props.app().publicUrl();
        this.publicUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ------------------------------------------------------------------ start

    @Transactional
    public ContributeResult contribute(long poolId, AuthUser user, ContributeRequest req) {
        PaymentProvider provider = runtime.require();
        if (!provider.hostedCheckout()) return funding.contribute(poolId, user, req);
        PoolRow p = funding.checkContribution(funding.pool(poolId), provider, user, req);
        long recent = jdbc.sql("""
                        SELECT count(*) FROM funding_pending_contributions
                        WHERE contributor_id = ? AND status = 'PENDING' AND created_at > now() - interval '1 hour'""")
                .param(user.id()).query(Long.class).single();
        if (recent >= MAX_PENDING_PER_HOUR) {
            throw ApiException.badRequest("You have several unfinished checkouts. Finish or cancel one, or try again later.");
        }
        String kind = req.recurring() ? "RECURRING" : "ONE_TIME";
        long pendingId = jdbc.sql("""
                        INSERT INTO funding_pending_contributions (pool_id, contributor_id, kind, amount_minor, currency, show_name, provider, mode)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(p.id(), user.id(), kind, req.amountMinor(), p.currency(), req.showName(), provider.id(), provider.mode())
                .query(Long.class).single();

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(META_POOL, Long.toString(p.id()));
        meta.put(META_CONTRIBUTOR, Long.toString(user.id()));
        meta.put(META_PENDING, Long.toString(pendingId));
        meta.put(META_ANONYMOUS, Boolean.toString(!req.showName()));
        meta.put(META_KIND, kind);
        String slug = funding.poolRef(p.id()).community().slug();
        String pageUrl = publicUrl + "/funding/pools/" + p.id();
        CheckoutRequest request = new CheckoutRequest(req.recurring(), req.amountMinor(), p.currency(),
                truncate("Howlers moderator pool: " + p.title() + " (c/" + slug + ")", 200),
                req.recurring() ? "Howlers: monthly contribution to the c/" + slug + " moderator pool until you cancel or the pool's period ends."
                        : "Howlers: one-time contribution to the c/" + slug + " moderator pool.",
                pageUrl + "?checkout=success&session_id={CHECKOUT_SESSION_ID}",
                pageUrl + "?checkout=cancelled&pending=" + pendingId,
                Long.toString(pendingId), meta, expiresAt(p), "howlers-pending-contribution-" + pendingId);
        CheckoutSession s;
        try {
            s = provider.startCheckout(request);
        } catch (RuntimeException e) {
            log.warn("Starting checkout for pending contribution {} failed: {}", pendingId, e.getMessage());
            throw providerError("The payment provider couldn't start a checkout. Nothing was charged; please try again.");
        }
        if (s == null || s.id() == null || s.url() == null) throw providerError("The payment provider returned no checkout page.");
        jdbc.sql("UPDATE funding_pending_contributions SET checkout_session_id = ? WHERE id = ?").params(s.id(), pendingId).update();
        return new ContributeResult(null, null, new CheckoutStart(pendingId, s.url(), provider.id(), provider.mode()));
    }

    /** Stripe accepts 30 minutes to 24 hours; a session never outlives the pool's period by more than it must. */
    private static long expiresAt(PoolRow p) {
        Instant now = Instant.now();
        Instant min = now.plus(Duration.ofMinutes(31)), max = now.plus(Duration.ofHours(23));
        Instant at = p.periodEnd().isBefore(min) ? min : p.periodEnd().isAfter(max) ? max : p.periodEnd();
        return at.getEpochSecond();
    }

    // ------------------------------------------------------------------ return from checkout

    /**
     * The contributor is back on the success URL: ask the provider (server-side, never trusting the browser) whether the
     * session was paid, and confirm it if so. Safe to poll; a confirmed contribution answers from the database.
     */
    @Transactional
    public CheckoutStatus confirm(AuthUser user, String sessionId) {
        PaymentProvider provider = hosted();
        Pending row = lockPending("checkout_session_id = ?", sessionId).filter(r -> r.contributorId() == user.id())
                .orElseThrow(() -> ApiException.notFound("Checkout"));
        if (!"PENDING".equals(row.status())) return status(row.id());
        limiter.check("funding-confirm:" + user.id(), 240, Duration.ofHours(1));
        CheckoutSession s;
        try {
            s = provider.retrieveCheckout(sessionId);
        } catch (RuntimeException e) {
            log.warn("Retrieving checkout session for pending contribution {} failed: {}", row.id(), e.getMessage());
            throw providerError("Couldn't reach the payment provider to confirm this payment. It will be confirmed when the provider reports it.");
        }
        apply(row, s, provider);
        return status(row.id());
    }

    /** The contributor left Checkout: expire the session so it can't be paid later, unless it was paid after all. */
    @Transactional
    public CheckoutStatus cancel(AuthUser user, long pendingId) {
        PaymentProvider provider = hosted();
        Pending row = lockPending("id = ?", pendingId).filter(r -> r.contributorId() == user.id())
                .orElseThrow(() -> ApiException.notFound("Checkout"));
        if (!"PENDING".equals(row.status())) return status(row.id());
        if (row.sessionId() != null) {
            CheckoutSession s;
            try {
                s = provider.expireCheckout(row.sessionId());
            } catch (RuntimeException e) {
                log.warn("Expiring checkout session for pending contribution {} failed: {}", row.id(), e.getMessage());
                throw providerError("Couldn't reach the payment provider. Nothing was recorded; please try again.");
            }
            if (s.paid()) {
                apply(row, s, provider);
                return status(row.id());
            }
        }
        resolve(row.id(), "CANCELLED", "You left the checkout. Nothing was charged.");
        return status(row.id());
    }

    @Transactional(readOnly = true)
    public CheckoutStatus get(AuthUser user, long pendingId) {
        Pending row = findPending("id = ?", pendingId).filter(r -> r.contributorId() == user.id())
                .orElseThrow(() -> ApiException.notFound("Checkout"));
        return status(row.id());
    }

    // ------------------------------------------------------------------ the one place a checkout becomes money

    /**
     * Applies what the provider says about a session to its (locked) pending row. Records the contribution, and for a
     * monthly one the subscription and its first paid invoice, at most once: the pending row is locked, and every charge
     * and invoice id is unique. Returns an outcome for the webhook log.
     */
    String apply(Pending row, CheckoutSession s, PaymentProvider provider) {
        if (!"PENDING".equals(row.status())) return "already " + row.status().toLowerCase(Locale.ROOT);
        String metaPending = s.metadata() == null ? null : s.metadata().get(META_PENDING);
        if ((row.sessionId() != null && !row.sessionId().equals(s.id())) || (metaPending != null && !metaPending.equals(Long.toString(row.id())))) {
            log.warn("Checkout session doesn't match pending contribution {}; ignored", row.id());
            return "session does not match";
        }
        if ("expired".equals(s.status())) {
            resolve(row.id(), "EXPIRED", "The checkout expired before it was paid. Nothing was charged.");
            return "expired";
        }
        if (!s.paid()) return "not paid yet";
        if ((s.amountTotal() != null && s.amountTotal() != row.amount())
                || (s.currency() != null && !s.currency().equalsIgnoreCase(row.currency()))) {
            log.error("Checkout for pending contribution {} was paid with a different amount or currency; not recorded", row.id());
            resolve(row.id(), "FAILED", "The paid amount didn't match. Nothing was recorded; contact the moderators for a refund.");
            return "amount mismatch";
        }
        PoolRow p = funding.lockPool(row.poolId());
        if (!"OPEN".equals(p.status())) {
            log.warn("Pending contribution {} was paid after pool {} closed; not recorded. Refund it in the Stripe dashboard.", row.id(), p.id());
            if (row.recurring() && s.subscriptionRef() != null) cancelQuietly(provider, s.subscriptionRef());
            resolve(row.id(), "FAILED", "The pool closed before the payment was confirmed, so it wasn't added. The moderators will refund it.");
            return "pool closed";
        }
        Long contributionId;
        Long subscriptionId = null;
        if (!row.recurring()) {
            String ref = s.paymentRef() != null ? s.paymentRef() : s.id();
            contributionId = existingContribution(provider, ref);
            if (contributionId == null) {
                contributionId = funding.recordContribution(p, row.contributorId(), null, null, "ONE_TIME", row.amount(), row.showName(),
                        provider, ref, s.paymentRef());
            }
        } else {
            if (s.subscriptionRef() == null) return "no subscription on session";
            subscriptionId = ensureSubscription(row, s.subscriptionRef(), provider);
            contributionId = s.invoiceRef() == null ? null : recordInvoice(subscriptionId, s.invoiceRef(), null, row.amount(), provider);
        }
        jdbc.sql("""
                        UPDATE funding_pending_contributions SET status = 'CONFIRMED', status_reason = NULL, contribution_id = ?,
                          subscription_id = ?, resolved_at = now() WHERE id = ?""")
                .params(contributionId, subscriptionId, row.id()).update();
        return "confirmed";
    }

    private Long ensureSubscription(Pending row, String subscriptionRef, PaymentProvider provider) {
        Long id = jdbc.sql("SELECT id FROM funding_subscriptions WHERE provider = ? AND provider_ref = ?").params(provider.id(), subscriptionRef)
                .query(Long.class).optional().orElse(null);
        if (id != null) return id;
        PoolRow p = funding.pool(row.poolId());
        // Stripe bills each cycle, so there's no next_charge_at for the demo job to act on.
        return jdbc.sql("""
                        INSERT INTO funding_subscriptions (pool_id, contributor_id, amount_minor, currency, show_name, provider, provider_ref,
                          demo, mode, next_charge_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL) RETURNING id""")
                .params(p.id(), row.contributorId(), row.amount(), p.currency(), row.showName(), provider.id(), subscriptionRef, p.demo(), p.mode())
                .query(Long.class).single();
    }

    /**
     * Records one paid invoice of a subscription as the next cycle, once: an invoice id already recorded returns its
     * contribution. Returns null when the pool no longer takes money.
     */
    private Long recordInvoice(long subscriptionId, String invoiceId, String paymentRef, long amountPaid, PaymentProvider provider) {
        record S(long poolId, Long userId, boolean showName, int cycles, String currency) {}
        S s = jdbc.sql("SELECT pool_id, contributor_id, show_name, cycles_charged, currency FROM funding_subscriptions WHERE id = ? FOR UPDATE")
                .param(subscriptionId)
                .query((rs, i) -> new S(rs.getLong(1), (Long) rs.getObject(2), rs.getBoolean(3), rs.getInt(4), rs.getString(5))).single();
        Long existing = existingContribution(provider, invoiceId);
        if (existing != null) return existing;
        PoolRow p = funding.lockPool(s.poolId());
        if (!"OPEN".equals(p.status()) || s.userId() == null) {
            log.warn("Invoice for subscription {} was paid but pool {} no longer takes money; not recorded. Refund it in the Stripe dashboard.",
                    subscriptionId, p.id());
            return null;
        }
        int cycle = s.cycles() + 1;
        long cid = funding.recordContribution(p, s.userId(), subscriptionId, cycle, "RECURRING", amountPaid, s.showName(), provider, invoiceId,
                paymentRef);
        jdbc.sql("UPDATE funding_subscriptions SET cycles_charged = ?, payment_failed_at = NULL WHERE id = ?").params(cycle, subscriptionId).update();
        return cid;
    }

    private Long existingContribution(PaymentProvider provider, String chargeRef) {
        return jdbc.sql("SELECT id FROM funding_contributions WHERE provider = ? AND provider_charge_id = ?").params(provider.id(), chargeRef)
                .query(Long.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ webhooks

    /**
     * Provider webhooks. Instant providers go to {@link FundingService#webhook}; hosted ones are verified here (Stripe:
     * {@code Stripe-Signature}), de-duplicated by event id in the same transaction as their effect, and applied.
     * An exception rolls everything back, so the provider's retry applies the event.
     */
    @Transactional
    public WebhookResult webhook(String providerId, String payload, Function<String, String> header) {
        PaymentProvider provider = runtime.require();
        if (!provider.id().equals(providerId)) throw ApiException.notFound("Webhook endpoint");
        if (!provider.hostedCheckout()) return funding.webhook(providerId, payload, header.apply(provider.signatureHeader()));
        WebhookEvent event;
        try {
            event = provider.verifyWebhook(payload, header.apply(provider.signatureHeader()));
        } catch (WebhookVerificationException e) {
            throw ApiException.badRequest("Invalid webhook: " + e.getMessage());
        }
        if (!funding.claimEvent(provider.id(), event)) return new WebhookResult("duplicate", event.id());
        String outcome = switch (event.type()) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> onSessionCompleted(event.data(), provider);
            case "checkout.session.async_payment_failed" -> onSessionClosed(event.data(), "FAILED", "The payment failed. Nothing was charged.");
            case "checkout.session.expired" -> onSessionClosed(event.data(), "EXPIRED", "The checkout expired before it was paid. Nothing was charged.");
            case "invoice.paid" -> onInvoicePaid(event.data(), provider);
            case "invoice.payment_failed" -> onInvoiceFailed(event.data(), provider);
            case "customer.subscription.deleted" -> onSubscriptionDeleted(event.data(), provider);
            case "charge.refunded" -> onChargeRefunded(event.data(), provider);
            // Stripe Connect payouts (D-046, D-047): same signature check, same event-id de-duplication.
            case "account.updated" -> connect.onAccountUpdated(event.data());
            case "transfer.created", "transfer.updated", "transfer.reversed" -> payouts.onTransferEvent(event.data());
            case "payout.paid", "payout.failed", "payout.canceled" -> connect.onPayoutEvent(event);
            default -> "ignored";
        };
        funding.recordOutcome(provider.id(), event.id(), outcome);
        return new WebhookResult("processed", event.id());
    }

    private String onSessionCompleted(Map<String, Object> object, PaymentProvider provider) {
        CheckoutSession s = StripePaymentProvider.session(object);
        Pending row = pendingFor(s.id(), s.metadata().get(META_PENDING));
        return row == null ? "unknown checkout session" : apply(row, s, provider);
    }

    private String onSessionClosed(Map<String, Object> object, String status, String reason) {
        CheckoutSession s = StripePaymentProvider.session(object);
        Pending row = pendingFor(s.id(), s.metadata().get(META_PENDING));
        if (row == null) return "unknown checkout session";
        if (!"PENDING".equals(row.status())) return "already " + row.status().toLowerCase(Locale.ROOT);
        resolve(row.id(), status, reason);
        return status.toLowerCase(Locale.ROOT);
    }

    private String onInvoicePaid(Map<String, Object> invoice, PaymentProvider provider) {
        String invoiceId = StripePaymentProvider.str(invoice.get("id"));
        String subRef = StripePaymentProvider.invoiceSubscription(invoice);
        Long paid = StripePaymentProvider.num(invoice.get("amount_paid"));
        if (invoiceId == null || subRef == null) return "not a subscription invoice";
        if (paid == null || paid <= 0) return "nothing paid";
        Long subId = jdbc.sql("SELECT id FROM funding_subscriptions WHERE provider = ? AND provider_ref = ?").params(provider.id(), subRef)
                .query(Long.class).optional().orElse(null);
        Pending row = null;
        if (subId == null) {
            // The first invoice can arrive before checkout.session.completed: find the contribution through the metadata.
            row = pendingFor(null, StripePaymentProvider.invoiceSubscriptionMetadata(invoice).get(META_PENDING));
            if (row == null || !row.recurring()) return "unknown subscription";
            if (!"PENDING".equals(row.status())) return "pending contribution already " + row.status().toLowerCase(Locale.ROOT);
            subId = ensureSubscription(row, subRef, provider);
        }
        String currency = StripePaymentProvider.str(invoice.get("currency"));
        String subCurrency = jdbc.sql("SELECT currency FROM funding_subscriptions WHERE id = ?").param(subId).query(String.class).single();
        if (currency != null && !currency.equalsIgnoreCase(subCurrency)) return "currency mismatch, not recorded";
        boolean known = existingContribution(provider, invoiceId) != null;
        Long cid = recordInvoice(subId, invoiceId, StripePaymentProvider.invoicePaymentIntent(invoice), paid, provider);
        if (row != null && cid != null) {
            jdbc.sql("""
                            UPDATE funding_pending_contributions SET status = 'CONFIRMED', contribution_id = ?, subscription_id = ?, resolved_at = now()
                            WHERE id = ? AND status = 'PENDING'""")
                    .params(cid, subId, row.id()).update();
        }
        if (cid == null) return "pool closed, not recorded";
        return known ? "invoice already recorded" : "invoice recorded";
    }

    private String onInvoiceFailed(Map<String, Object> invoice, PaymentProvider provider) {
        String subRef = StripePaymentProvider.invoiceSubscription(invoice);
        if (subRef == null) return "not a subscription invoice";
        int n = jdbc.sql("UPDATE funding_subscriptions SET payment_failed_at = now() WHERE provider = ? AND provider_ref = ? AND status = 'ACTIVE'")
                .params(provider.id(), subRef).update();
        return n > 0 ? "renewal payment failed; Stripe retries it" : "no active subscription";
    }

    private String onSubscriptionDeleted(Map<String, Object> sub, PaymentProvider provider) {
        String ref = StripePaymentProvider.str(sub.get("id"));
        if (ref == null) return "no subscription id";
        int n = jdbc.sql("""
                        UPDATE funding_subscriptions SET status = 'CANCELLED', next_charge_at = NULL, cancelled_at = now(), cancelled_by = 'PROVIDER'
                        WHERE provider = ? AND provider_ref = ? AND status = 'ACTIVE'""")
                .params(provider.id(), ref).update();
        return n > 0 ? "subscription cancelled" : "no active subscription";
    }

    /** {@code amount_refunded} is cumulative, so each refund state is recorded once and partial refunds add up correctly. */
    private String onChargeRefunded(Map<String, Object> charge, PaymentProvider provider) {
        String chargeId = StripePaymentProvider.str(charge.get("id"));
        String pi = StripePaymentProvider.str(charge.get("payment_intent"));
        Long refunded = StripePaymentProvider.num(charge.get("amount_refunded"));
        if (chargeId == null || refunded == null || refunded <= 0) return "nothing refunded";
        Long cid = null;
        if (pi != null) {
            cid = jdbc.sql("""
                            SELECT id FROM funding_contributions WHERE provider = ? AND (provider_payment_ref = ? OR provider_charge_id = ?)
                            ORDER BY id LIMIT 1""")
                    .params(provider.id(), pi, pi).query(Long.class).optional().orElse(null);
        }
        String invoice = StripePaymentProvider.str(charge.get("invoice"));
        if (cid == null && invoice == null && pi != null) invoice = provider.invoiceForPayment(pi); // newer API versions omit charge.invoice
        if (cid == null && invoice != null) cid = existingContribution(provider, invoice);
        if (cid == null) return "refund for an unrecorded payment";
        String currency = StripePaymentProvider.str(charge.get("currency"));
        String own = jdbc.sql("SELECT currency FROM funding_contributions WHERE id = ?").param(cid).query(String.class).single();
        if (currency != null && !currency.equalsIgnoreCase(own)) return "currency mismatch, not recorded";
        Long rid = funding.recordRefund(cid, refunded - funding.refundedOf(cid), "stripe:" + chargeId + ":" + refunded);
        if (rid != null) payouts.flagRefund(rid); // after a payout nothing is clawed back automatically: an admin reviews it
        return rid == null ? "refund already recorded" : "refund recorded";
    }

    // ------------------------------------------------------------------ rows

    record Pending(long id, long poolId, long contributorId, String kind, long amount, String currency, boolean showName, String provider,
                   String mode, String sessionId, String status, String reason, Long contributionId, Long subscriptionId) {
        boolean recurring() {
            return "RECURRING".equals(kind);
        }
    }

    private static final String PENDING_SQL = """
            SELECT id, pool_id, contributor_id, kind, amount_minor, currency, show_name, provider, mode, checkout_session_id, status,
              status_reason, contribution_id, subscription_id
            FROM funding_pending_contributions WHERE""";

    private java.util.Optional<Pending> findPending(String where, Object param, boolean lock) {
        return jdbc.sql(PENDING_SQL + " " + where + (lock ? " FOR UPDATE" : "")).param(param)
                .query((rs, i) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                        rs.getBoolean(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                        (Long) rs.getObject(13), (Long) rs.getObject(14)))
                .optional();
    }

    private java.util.Optional<Pending> findPending(String where, Object param) {
        return findPending(where, param, false);
    }

    private java.util.Optional<Pending> lockPending(String where, Object param) {
        return findPending(where, param, true);
    }

    /** The pending row for a session, by session id or (when the session id wasn't stored yet) by the metadata id. */
    private Pending pendingFor(String sessionId, String metadataId) {
        if (sessionId != null) {
            var bySession = lockPending("checkout_session_id = ?", sessionId);
            if (bySession.isPresent()) return bySession.get();
        }
        if (metadataId == null) return null;
        long id;
        try {
            id = Long.parseLong(metadataId);
        } catch (NumberFormatException e) {
            return null;
        }
        return lockPending("id = ?", id).orElse(null);
    }

    private void resolve(long pendingId, String status, String reason) {
        jdbc.sql("UPDATE funding_pending_contributions SET status = ?, status_reason = ?, resolved_at = ? WHERE id = ? AND status = 'PENDING'")
                .params(status, reason, Timestamp.from(Instant.now()), pendingId).update();
    }

    private CheckoutStatus status(long pendingId) {
        Pending r = findPending("id = ?", pendingId).orElseThrow(() -> ApiException.notFound("Checkout"));
        return new CheckoutStatus(r.id(), r.status(), r.reason(), funding.poolRef(r.poolId()), r.kind(), Money.of(r.amount(), r.currency(), r.mode()),
                r.contributionId() == null ? null : funding.contribution(r.contributionId()),
                r.subscriptionId() == null ? null : funding.subscription(r.subscriptionId()));
    }

    private PaymentProvider hosted() {
        PaymentProvider provider = runtime.require();
        if (!provider.hostedCheckout()) throw ApiException.notFound("Checkout");
        return provider;
    }

    private static void cancelQuietly(PaymentProvider provider, String subscriptionRef) {
        try {
            provider.cancel(subscriptionRef);
        } catch (RuntimeException e) {
            log.warn("Cancelling a provider subscription failed; cancel it in the provider dashboard: {}", e.getMessage());
        }
    }

    private static ApiException providerError(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", message);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

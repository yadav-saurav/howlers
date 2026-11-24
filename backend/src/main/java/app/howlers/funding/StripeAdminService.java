package app.howlers.funding;

import app.howlers.common.config.AppProperties;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.funding.ConnectDtos.PayoutRun;
import app.howlers.funding.ConnectDtos.ReviewFlag;
import app.howlers.funding.ConnectDtos.StripeStatus;
import app.howlers.funding.ConnectDtos.WebhookSeen;
import app.howlers.funding.payment.ConnectRuntime;
import app.howlers.funding.payment.FundingRuntime;
import app.howlers.funding.payment.PaymentProvider;
import app.howlers.funding.payment.StripePaymentProvider;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin "Stripe status" panel and the manual-review queue for payouts (D-047). Never returns a secret: only whether
 * each one is set.
 */
@Service
public class StripeAdminService {
    /** Events for the platform endpoint ("Your account" in the dashboard). */
    static final List<String> PLATFORM_EVENTS = List.of("checkout.session.completed", "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed", "checkout.session.expired", "invoice.paid", "invoice.payment_failed",
            "customer.subscription.deleted", "charge.refunded", "transfer.created", "transfer.updated", "transfer.reversed");
    /** Events for the connected-accounts endpoint ("Connected accounts" in the dashboard). */
    static final List<String> CONNECT_EVENTS = List.of("account.updated", "payout.paid", "payout.failed", "payout.canceled");

    private final JdbcClient jdbc;
    private final FundingRuntime funding;
    private final ConnectRuntime connect;
    private final FundingProperties props;
    private final FundingService service;
    private final PayoutService payouts;
    private final UserDirectory directory;
    private final String publicUrl;

    public StripeAdminService(JdbcClient jdbc, FundingRuntime funding, ConnectRuntime connect, FundingProperties props, FundingService service,
                              PayoutService payouts, UserDirectory directory, AppProperties app) {
        this.jdbc = jdbc;
        this.funding = funding;
        this.connect = connect;
        this.props = props;
        this.service = service;
        this.payouts = payouts;
        this.directory = directory;
        String url = app.app() == null || app.app().publicUrl() == null ? "http://localhost:5173" : app.app().publicUrl();
        this.publicUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void requireAdmin(AuthUser user) {
        if (user == null || !user.isAdmin()) throw ApiException.forbidden("Admins only");
    }

    @Transactional(readOnly = true)
    public StripeStatus status(AuthUser user) {
        requireAdmin(user);
        PaymentProvider p = funding.provider();
        boolean stripe = p instanceof StripePaymentProvider;
        boolean hook = stripe && ((StripePaymentProvider) p).webhooksConfigured();
        boolean connectHook = stripe && ((StripePaymentProvider) p).connectWebhooksConfigured();
        WebhookSeen last = jdbc.sql("""
                        SELECT event_type, outcome, received_at FROM funding_webhook_events WHERE provider = ? ORDER BY received_at DESC LIMIT 1""")
                .param(StripePaymentProvider.ID)
                .query((rs, i) -> new WebhookSeen(rs.getString(1), rs.getString(2), FundingService.ts(rs, 3))).optional().orElse(null);
        long day = jdbc.sql("SELECT count(*) FROM funding_webhook_events WHERE provider = ? AND received_at > now() - interval '24 hours'")
                .param(StripePaymentProvider.ID).query(Long.class).single();
        record A(long all, long enabled) {}
        A a = jdbc.sql("SELECT count(*), count(*) FILTER (WHERE status = 'ENABLED') FROM funding_connected_accounts")
                .query((rs, i) -> new A(rs.getLong(1), rs.getLong(2))).single();
        Map<String, Long> byStatus = new java.util.HashMap<>();
        jdbc.sql("SELECT status, count(*) FROM funding_payouts GROUP BY status").query((rs, i) -> byStatus.put(rs.getString(1), rs.getLong(2))).list();
        long reviews = jdbc.sql("SELECT count(*) FROM funding_review_flags WHERE resolved_at IS NULL").query(Long.class).single();
        return new StripeStatus(funding.enabled(), p == null ? null : p.id(), p == null ? null : p.mode(), p == null ? null : p.label(),
                stripe || !props.stripeOrEmpty().key().isEmpty(), hook, connectHook, last, day, connect.enabled(),
                connect.enabled() ? null : connect.disabledReason(), connect.country(),
                publicUrl + "/api/v1/funding/webhooks/stripe", PLATFORM_EVENTS, CONNECT_EVENTS, a.all(), a.enabled(),
                byStatus.getOrDefault("HELD", 0L), byStatus.getOrDefault("FAILED", 0L), byStatus.getOrDefault("PAID", 0L),
                byStatus.getOrDefault("REVERSED", 0L), reviews);
    }

    @Transactional(readOnly = true)
    public List<ReviewFlag> reviews(AuthUser user, boolean openOnly) {
        requireAdmin(user);
        record R(long id, String kind, long poolId, Long payoutId, Long moderatorId, long amount, String currency, String mode, String detail,
                 Instant at, Instant resolvedAt, Long resolvedBy, String note) {}
        List<R> rows = jdbc.sql("""
                        SELECT f.id, f.kind, f.pool_id, f.payout_id, p.moderator_id, f.amount_minor, f.currency, f.mode, f.detail, f.created_at,
                          f.resolved_at, f.resolved_by, f.resolution_note
                        FROM funding_review_flags f LEFT JOIN funding_payouts p ON p.id = f.payout_id
                        WHERE (? = FALSE OR f.resolved_at IS NULL) ORDER BY f.id DESC LIMIT 200""")
                .param(openOnly)
                .query((rs, i) -> new R(rs.getLong(1), rs.getString(2), rs.getLong(3), (Long) rs.getObject(4), (Long) rs.getObject(5),
                        rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9), FundingService.ts(rs, 10), FundingService.ts(rs, 11),
                        (Long) rs.getObject(12), rs.getString(13)))
                .list();
        Map<Long, UserSummary> users = directory.summaries(rows.stream().flatMap(r -> Stream.of(r.moderatorId(), r.resolvedBy()))
                .filter(Objects::nonNull).toList());
        return rows.stream().map(r -> new ReviewFlag(r.id(), r.kind(), service.poolRef(r.poolId()), r.payoutId(),
                r.moderatorId() == null ? null : users.get(r.moderatorId()), Money.of(r.amount(), r.currency(), r.mode()), r.detail(), r.at(),
                r.resolvedAt(), r.resolvedBy() == null ? null : users.get(r.resolvedBy()), r.note())).toList();
    }

    /** Marks a review flag handled, with a note of what was done (recorded in the admin audit log too). */
    @Transactional
    public ReviewFlag resolve(AuthUser user, long id, String note) {
        requireAdmin(user);
        int n = jdbc.sql("""
                        UPDATE funding_review_flags SET resolved_at = now(), resolved_by = ?, resolution_note = ? WHERE id = ? AND resolved_at IS NULL""")
                .params(user.id(), note.strip(), id).update();
        if (n == 0 && jdbc.sql("SELECT count(*) FROM funding_review_flags WHERE id = ?").param(id).query(Long.class).single() == 0) {
            throw ApiException.notFound("Review flag");
        }
        if (n > 0) {
            jdbc.sql("INSERT INTO admin_actions (admin_id, action, reason) VALUES (?, 'RESOLVE_PAYOUT_REVIEW', ?)")
                    .params(user.id(), PayoutService.trim("Payout review #" + id + ": " + note.strip(), 300)).update();
        }
        return reviews(user, false).stream().filter(f -> f.id() == id).findFirst().orElseThrow(() -> ApiException.notFound("Review flag"));
    }

    /** Runs the payout job now (instead of waiting for the schedule). */
    public PayoutRun runPayouts(AuthUser user) {
        requireAdmin(user);
        if (!connect.enabled()) throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "CONNECT_DISABLED", connect.disabledReason());
        return payouts.releaseDue(Instant.now());
    }
}

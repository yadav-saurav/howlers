package app.howlers.funding;

import app.howlers.common.config.AppProperties;
import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.funding.ConnectDtos.ConnectAccount;
import app.howlers.funding.ConnectDtos.MyPayouts;
import app.howlers.funding.ConnectDtos.OnboardingLink;
import app.howlers.funding.ConnectDtos.Payout;
import app.howlers.funding.payment.ConnectRuntime;
import app.howlers.funding.payment.PaymentProvider.WebhookEvent;
import app.howlers.funding.payment.StripeGateway;
import app.howlers.funding.payment.StripeGateway.AccountLink;
import app.howlers.funding.payment.StripeGateway.ConnectedAccount;
import app.howlers.funding.payment.StripePaymentProvider;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moderators' Stripe Express accounts (D-046). A moderator starts onboarding on Stripe's hosted page, comes back, and
 * Howlers reads the account's status flags from Stripe (on return, from {@code account.updated}, and from a job, so it
 * works without webhooks too). Only the account id and those flags are stored; Stripe keeps identity and bank details.
 * When an account becomes ENABLED, everything held for that moderator is sent.
 */
@Service
public class ConnectService {
    private static final Logger log = LoggerFactory.getLogger(ConnectService.class);
    static final String PAGE = "/funding/payouts";

    private final JdbcClient jdbc;
    private final ConnectRuntime connect;
    private final FundingService funding;
    private final PayoutService payouts;
    private final RateLimiter limiter;
    private final String publicUrl;
    private final TransactionTemplate tx;

    public ConnectService(JdbcClient jdbc, ConnectRuntime connect, FundingService funding, PayoutService payouts, RateLimiter limiter,
                          AppProperties props, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.connect = connect;
        this.funding = funding;
        this.payouts = payouts;
        this.limiter = limiter;
        String url = props.app() == null || props.app().publicUrl() == null ? "http://localhost:5173" : props.app().publicUrl();
        this.publicUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ status

    /** ENABLED: Stripe can send money to the account. REQUIREMENTS_DUE: it needs more. PENDING: not finished or being verified. */
    static String statusOf(ConnectedAccount a) {
        if (a.payoutsEnabled() && "active".equals(a.transfersCapability())) return "ENABLED";
        if (a.detailsSubmitted() && (a.requirementsDue() > 0 || a.disabledReason() != null)) return "REQUIREMENTS_DUE";
        return "PENDING";
    }

    static String messageFor(String status, boolean detailsSubmitted, int due) {
        return switch (status) {
            case "NOT_STARTED" -> "Connect a Stripe account to receive your share of closed pools.";
            case "ENABLED" -> "Payouts are enabled. Your shares are sent to your Stripe account when pools close.";
            case "REQUIREMENTS_DUE" -> "Stripe needs more information" + (due > 0 ? " (" + due + (due == 1 ? " item" : " items") + ")" : "")
                    + " before it can pay you. Resume onboarding to provide it.";
            default -> detailsSubmitted ? "Stripe is checking your details. This usually takes a few minutes."
                    : "Onboarding isn't finished. Resume it to receive payouts.";
        };
    }

    private record AccountRow(long id, long userId, String accountId, String status, boolean detailsSubmitted, boolean payoutsEnabled,
                              boolean transfersActive, int due, String disabledReason, String lastPayoutStatus, Instant lastPayoutAt,
                              Instant syncedAt) {}

    private static final String ACCOUNT_SQL = """
            SELECT id, user_id, account_id, status, details_submitted, payouts_enabled, transfers_active, requirements_due, disabled_reason,
              last_payout_status, last_payout_at, synced_at
            FROM funding_connected_accounts WHERE""";

    private Optional<AccountRow> account(String where, Object... params) {
        return jdbc.sql(ACCOUNT_SQL + " " + where).params(params)
                .query((rs, i) -> new AccountRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        rs.getBoolean(6), rs.getBoolean(7), rs.getInt(8), rs.getString(9), rs.getString(10), FundingService.ts(rs, 11),
                        FundingService.ts(rs, 12)))
                .optional();
    }

    private Optional<AccountRow> accountOf(long userId) {
        if (!connect.enabled()) return Optional.empty();
        return account("user_id = ? AND provider = ? AND mode = ?", userId, StripePaymentProvider.ID, connect.mode());
    }

    private static ConnectAccount dto(Optional<AccountRow> row) {
        if (row.isEmpty()) {
            return new ConnectAccount("NOT_STARTED", null, false, false, false, 0, null, messageFor("NOT_STARTED", false, 0), null, null, null);
        }
        AccountRow a = row.get();
        String id = a.accountId();
        String hint = "acct_…" + id.substring(Math.max(0, id.length() - 4));
        return new ConnectAccount(a.status(), hint, a.detailsSubmitted(), a.payoutsEnabled(), a.transfersActive(), a.due(), a.disabledReason(),
                messageFor(a.status(), a.detailsSubmitted(), a.due()), a.lastPayoutStatus(), a.lastPayoutAt(), a.syncedAt());
    }

    private boolean eligible(long userId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM community_members WHERE user_id = ? AND role IN ('OWNER', 'ADMIN', 'MODERATOR'))
                            OR EXISTS (SELECT 1 FROM funding_payouts WHERE moderator_id = ?)""")
                .params(userId, userId).query(Boolean.class).single());
    }

    @Transactional(readOnly = true)
    public MyPayouts my(AuthUser user) {
        Optional<AccountRow> acct = accountOf(user.id());
        String accountStatus = acct.map(AccountRow::status).orElse("NOT_STARTED");
        record R(long id, long poolId, long amount, String currency, String mode, String status, String reason, long transferred, long reversed,
                 Instant paidAt, Instant at) {}
        List<R> rows = jdbc.sql("""
                        SELECT id, pool_id, amount_minor, currency, mode, status, status_reason, transferred_minor, reversed_minor, paid_at, created_at
                        FROM funding_payouts WHERE moderator_id = ? ORDER BY id DESC LIMIT 100""")
                .param(user.id())
                .query((rs, i) -> new R(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getLong(8), rs.getLong(9), FundingService.ts(rs, 10), FundingService.ts(rs, 11)))
                .list();
        List<Payout> list = rows.stream().map(r -> {
            boolean sameMode = connect.enabled() && r.mode().equals(connect.mode());
            String label = PayoutService.label(r.status(), r.mode(), r.reversed(), connect.enabled(), sameMode ? accountStatus : null);
            String reason = r.reason();
            if ("HELD".equals(r.status()) && connect.enabled() && !"ENABLED".equals(accountStatus)) reason = PayoutService.HELD_CONNECT;
            if ("HELD".equals(r.status()) && !connect.enabled()) reason = connect.disabledReason();
            return new Payout(r.id(), funding.poolRef(r.poolId()), Money.of(r.amount(), r.currency(), r.mode()), r.status(), label, reason,
                    Money.of(r.transferred() - r.reversed(), r.currency(), r.mode()), Money.of(r.reversed(), r.currency(), r.mode()), r.paidAt(), r.at());
        }).toList();
        Map<String, long[]> totals = new LinkedHashMap<>(); // currency|mode -> [held, paid]
        for (R r : rows) {
            long[] t = totals.computeIfAbsent(r.currency() + "|" + r.mode(), k -> new long[2]);
            long net = r.transferred() - r.reversed();
            t[0] += r.amount() - net;
            t[1] += net;
        }
        List<Money> held = new ArrayList<>(), paid = new ArrayList<>();
        totals.forEach((k, v) -> {
            String[] cm = k.split("\\|");
            held.add(Money.of(v[0], cm[0], cm[1]));
            paid.add(Money.of(v[1], cm[0], cm[1]));
        });
        String mode = connect.enabled() ? connect.mode() : null;
        String notice = connect.enabled()
                ? (Money.MODE_TEST.equals(mode) ? "Stripe Connect test mode: onboarding uses Stripe's test data and payouts move no real money."
                : "Payouts are sent through Stripe Connect to your connected account.")
                : connect.disabledReason();
        return new MyPayouts(connect.enabled(), mode, mode == null ? null : Money.labelFor(mode), notice, eligible(user.id()), dto(acct), list,
                held, paid);
    }

    // ------------------------------------------------------------------ onboarding

    /**
     * Starts or resumes onboarding: creates the Express account the first time (idempotently), then a fresh single-use
     * account link. Only for the signed-in user, and only for moderators (or anyone who has payouts).
     */
    @Transactional
    public OnboardingLink startOnboarding(AuthUser user) {
        StripeGateway gw = connect.require();
        if (!eligible(user.id())) throw ApiException.forbidden("Only moderators can connect a payout account");
        limiter.check("connect-onboarding:" + user.id(), 20, Duration.ofHours(1));
        AccountRow row = accountOf(user.id()).orElse(null);
        if (row == null) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("howlers_user_id", Long.toString(user.id()));
            ConnectedAccount a;
            try {
                a = gw.createExpressAccount(connect.country(), meta, "howlers-connect-account-" + user.id() + "-" + connect.mode());
            } catch (RuntimeException e) {
                log.warn("Creating a connected account for user {} failed: {}", user.id(), e.getMessage());
                throw providerError("Stripe couldn't create your payout account. " + hint(e));
            }
            if (a == null || a.id() == null) throw providerError("Stripe returned no account.");
            jdbc.sql("""
                            INSERT INTO funding_connected_accounts (user_id, provider, mode, account_id) VALUES (?, ?, ?, ?)
                            ON CONFLICT (user_id, provider, mode) DO NOTHING""")
                    .params(user.id(), StripePaymentProvider.ID, connect.mode(), a.id()).update();
            row = accountOf(user.id()).orElseThrow();
            save(row.accountId(), a);
        }
        AccountLink link;
        try {
            link = gw.createAccountLink(row.accountId(), publicUrl + PAGE + "?connect=refresh", publicUrl + PAGE + "?connect=return");
        } catch (RuntimeException e) {
            log.warn("Creating an onboarding link for user {} failed: {}", user.id(), e.getMessage());
            throw providerError("Stripe couldn't create an onboarding link. " + hint(e));
        }
        if (link == null || link.url() == null) throw providerError("Stripe returned no onboarding link.");
        return new OnboardingLink(link.url(), link.expiresAt());
    }

    /** Back from onboarding (or the user pressed "Check status"): read the account from Stripe and release held shares if ready. */
    @Transactional
    public MyPayouts refresh(AuthUser user) {
        StripeGateway gw = connect.require();
        AccountRow row = accountOf(user.id()).orElse(null);
        if (row != null) {
            limiter.check("connect-refresh:" + user.id(), 120, Duration.ofHours(1));
            ConnectedAccount a;
            try {
                a = gw.retrieveAccount(row.accountId());
            } catch (RuntimeException e) {
                log.warn("Retrieving connected account of user {} failed: {}", user.id(), e.getMessage());
                throw providerError("Couldn't reach Stripe to check your account. Try again in a moment.");
            }
            save(row.accountId(), a);
        }
        return my(user);
    }

    /**
     * Stores Stripe's flags for an account. When it is ENABLED, held payouts are sent after the transaction commits.
     * Returns the new status, or null for an account Howlers doesn't know.
     */
    String save(String accountId, ConnectedAccount a) {
        String status = statusOf(a);
        record U(long userId) {}
        U u = jdbc.sql("""
                        UPDATE funding_connected_accounts SET status = ?, details_submitted = ?, charges_enabled = ?, payouts_enabled = ?,
                          transfers_active = ?, requirements_due = ?, disabled_reason = ?, updated_at = now(), synced_at = now()
                        WHERE account_id = ? RETURNING user_id""")
                .params(status, a.detailsSubmitted(), a.chargesEnabled(), a.payoutsEnabled(), "active".equals(a.transfersCapability()),
                        a.requirementsDue(), PayoutService.trim(a.disabledReason(), 100), accountId)
                .query((rs, i) -> new U(rs.getLong(1))).optional().orElse(null);
        if (u == null) return null;
        if ("ENABLED".equals(status) && connect.enabled()) PayoutService.afterCommit(() -> payouts.releaseForModerator(u.userId()));
        return status;
    }

    // ------------------------------------------------------------------ webhooks (inside the webhook's transaction)

    /** {@code account.updated}: the event's object is the account itself. */
    String onAccountUpdated(Map<String, Object> object) {
        String id = StripePaymentProvider.str(object.get("id"));
        if (id == null) return "no account id";
        String status = save(id, account(object));
        return status == null ? "unknown account" : "account " + status.toLowerCase();
    }

    /** {@code payout.paid} / {@code payout.failed} / {@code payout.canceled} on a connected account: the bank payout's outcome. */
    String onPayoutEvent(WebhookEvent event) {
        if (event.account() == null) return "not a connected-account payout";
        String status = StripePaymentProvider.str(event.data().get("status"));
        int n = jdbc.sql("UPDATE funding_connected_accounts SET last_payout_status = ?, last_payout_at = now(), updated_at = now() WHERE account_id = ?")
                .params(PayoutService.trim(status == null ? event.type().substring("payout.".length()) : status, 20), event.account()).update();
        return n > 0 ? "bank payout " + (status == null ? "updated" : status) : "unknown account";
    }

    /** An Account object from a webhook, read as plain JSON. */
    static ConnectedAccount account(Map<String, Object> o) {
        Map<String, Object> caps = StripePaymentProvider.map(o.get("capabilities"));
        Map<String, Object> req = StripePaymentProvider.map(o.get("requirements"));
        int due = req.get("currently_due") instanceof List<?> l ? l.size() : 0;
        String transfers = caps.get("transfers") instanceof String s ? s : "unrequested";
        return new ConnectedAccount(StripePaymentProvider.str(o.get("id")), Boolean.TRUE.equals(o.get("details_submitted")),
                Boolean.TRUE.equals(o.get("charges_enabled")), Boolean.TRUE.equals(o.get("payouts_enabled")), transfers, due,
                StripePaymentProvider.str(req.get("disabled_reason")));
    }

    // ------------------------------------------------------------------ job

    /** Re-reads accounts that aren't enabled yet, so status moves on even without webhooks. Returns how many were synced. */
    public int syncStale(Instant now) {
        if (!connect.enabled()) return 0;
        List<String> ids = jdbc.sql("""
                        SELECT account_id FROM funding_connected_accounts WHERE provider = ? AND mode = ? AND status <> 'ENABLED'
                          AND (synced_at IS NULL OR synced_at < ?) ORDER BY synced_at NULLS FIRST LIMIT 20""")
                .params(StripePaymentProvider.ID, connect.mode(), Timestamp.from(now.minus(Duration.ofMinutes(10)))).query(String.class).list();
        int n = 0;
        for (String id : ids) {
            try {
                ConnectedAccount a = connect.gateway().retrieveAccount(id);
                tx.executeWithoutResult(s -> save(id, a));
                n++;
            } catch (RuntimeException e) {
                log.warn("Syncing a connected account failed: {}", e.getMessage());
            }
        }
        return n;
    }

    private static String hint(RuntimeException e) {
        if (e instanceof StripeGateway.StripeGatewayException g && g.getMessage() != null && g.getMessage().contains("signed up for Connect")) {
            return "Stripe Connect isn't activated on the platform's Stripe account yet; an admin must turn it on in the Stripe dashboard.";
        }
        return "Nothing was changed; please try again.";
    }

    private static ApiException providerError(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", message);
    }
}

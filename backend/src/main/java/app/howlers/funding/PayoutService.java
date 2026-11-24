package app.howlers.funding;

import app.howlers.funding.ConnectDtos.AllocationPayout;
import app.howlers.funding.ConnectDtos.PayoutReconciliation;
import app.howlers.funding.ConnectDtos.PayoutRun;
import app.howlers.funding.payment.ConnectRuntime;
import app.howlers.funding.payment.StripeGateway;
import app.howlers.funding.payment.StripeGateway.StripeGatewayException;
import app.howlers.funding.payment.StripeGateway.Transfer;
import app.howlers.funding.payment.StripeGateway.TransferRequest;
import app.howlers.funding.payment.StripePaymentProvider;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pays allocations out with Stripe Connect (D-046, D-047). When a Stripe pool closes, each allocation with a positive
 * amount gets a payout row. A payout becomes a Stripe Transfer (platform balance to the moderator's Express account) as
 * soon as the moderator's account is enabled; until then it is HELD. Every transfer and reversal is an append-only
 * PAYOUT / PAYOUT_REVERSAL ledger row, so allocations = transferred + held can be checked independently.
 *
 * <p>No double transfers: the payout row is locked, each transfer uses the idempotency key
 * {@code howlers-payout-{id}-{attempt}} (the attempt only moves on after Stripe definitively refused, never after an
 * unknown outcome), the transfer group is looked up before retrying after a failure, the transfer id is unique, and the
 * ledger's external reference is unique. Each payout is sent in its own transaction, after the close has committed.
 */
@Service
public class PayoutService {
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    static final String META_PAYOUT = "howlers_payout_id";
    static final String HELD_CONNECT = "Connect your account to receive it.";
    static final int REVIEW_AFTER_FAILURES = 3;
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final ConnectRuntime connect;
    private final TransactionTemplate tx;

    public PayoutService(JdbcClient jdbc, ConnectRuntime connect, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.connect = connect;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean enabled() {
        return connect.enabled();
    }

    /** Payouts exist for pools taken through Stripe (test or live); demo allocations are never paid out. */
    static boolean paysOut(String provider, String mode) {
        return StripePaymentProvider.ID.equals(provider) && !Money.MODE_DEMO.equals(mode);
    }

    // ------------------------------------------------------------------ creating payouts when a pool closes

    /** Called inside the close transaction, right after the allocations are written. Sending happens after commit. */
    void createForPool(long poolId, String provider, String mode) {
        if (!paysOut(provider, mode)) return;
        String reason = connect.enabled() ? HELD_CONNECT : connect.disabledReason();
        jdbc.sql("""
                        INSERT INTO funding_payouts (allocation_id, pool_id, moderator_id, amount_minor, currency, provider, mode, status_reason)
                        SELECT id, pool_id, moderator_id, amount_minor, currency, ?, mode, ? FROM funding_allocations
                        WHERE pool_id = ? AND amount_minor > 0
                        ON CONFLICT (allocation_id) DO NOTHING""")
                .params(provider, reason, poolId).update();
        if (connect.enabled()) afterCommit(() -> releaseForPool(poolId));
    }

    /** Runs {@code r} once the surrounding transaction has committed (or now, if there is none). Failures are logged. */
    static void afterCommit(Runnable r) {
        Runnable safe = () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("Releasing payouts after commit failed; the payout job retries: {}", e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    // ------------------------------------------------------------------ sending

    public PayoutRun releaseForPool(long poolId) {
        return run(jdbc.sql("SELECT id FROM funding_payouts WHERE pool_id = ? AND status IN ('HELD', 'FAILED') ORDER BY id")
                .param(poolId).query(Long.class).list(), false, Instant.now());
    }

    /** The moderator's account just became ready: send everything held for them now, failed ones included. */
    public PayoutRun releaseForModerator(long userId) {
        return run(jdbc.sql("SELECT id FROM funding_payouts WHERE moderator_id = ? AND status IN ('HELD', 'FAILED') ORDER BY id")
                .param(userId).query(Long.class).list(), true, Instant.now());
    }

    /** The job: held payouts whose moderator is ready, and failed ones whose retry time has come. */
    public PayoutRun releaseDue(Instant now) {
        if (!connect.enabled()) return new PayoutRun(0, 0, 0, 0);
        List<Long> ids = jdbc.sql("""
                        SELECT p.id FROM funding_payouts p
                        JOIN funding_connected_accounts a ON a.user_id = p.moderator_id AND a.provider = p.provider AND a.mode = p.mode
                        WHERE a.status = 'ENABLED' AND p.status IN ('HELD', 'FAILED') AND (p.next_attempt_at IS NULL OR p.next_attempt_at <= ?)
                        ORDER BY p.id LIMIT ?""")
                .params(Timestamp.from(now), BATCH).query(Long.class).list();
        return run(ids, false, now);
    }

    private PayoutRun run(List<Long> ids, boolean ignoreBackoff, Instant now) {
        int paid = 0, held = 0, failed = 0;
        for (long id : ids) {
            String outcome;
            try {
                outcome = tx.execute(s -> releaseOne(id, now, ignoreBackoff));
            } catch (RuntimeException e) {
                log.warn("Payout {} could not be processed; the job retries: {}", id, e.toString());
                outcome = "failed";
            }
            switch (outcome == null ? "" : outcome) {
                case "paid" -> paid++;
                case "held" -> held++;
                case "failed" -> failed++;
                default -> {}
            }
        }
        return new PayoutRun(ids.size(), paid, held, failed);
    }

    private record Row(long id, long poolId, long allocationId, long moderatorId, long amount, String currency, String provider, String mode,
                       String status, String transferId, long transferred, long reversed, int attempt, int failures, Instant nextAttempt) {}

    private static final String ROW_SQL = """
            SELECT id, pool_id, allocation_id, moderator_id, amount_minor, currency, provider, mode, status, transfer_id, transferred_minor,
              reversed_minor, attempt, failures, next_attempt_at
            FROM funding_payouts WHERE""";

    private Optional<Row> row(String where, Object param, String lock) {
        return jdbc.sql(ROW_SQL + " " + where + " " + lock).param(param)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getLong(11), rs.getLong(12), rs.getInt(13),
                        rs.getInt(14), FundingService.ts(rs, 15)))
                .optional();
    }

    /** The moderator's connected account in a mode, if ENABLED. */
    private String readyAccount(long userId, String provider, String mode) {
        return jdbc.sql("SELECT account_id FROM funding_connected_accounts WHERE user_id = ? AND provider = ? AND mode = ? AND status = 'ENABLED'")
                .params(userId, provider, mode).query(String.class).optional().orElse(null);
    }

    /** One payout, in its own transaction. Returns paid | held | failed | skipped. */
    String releaseOne(long id, Instant now, boolean ignoreBackoff) {
        // SKIP LOCKED: another node (or a concurrent webhook) is already on it; it will finish or the next run will.
        Row p = row("id = ?", id, "FOR UPDATE SKIP LOCKED").orElse(null);
        if (p == null || !("HELD".equals(p.status()) || "FAILED".equals(p.status()))) return "skipped";
        if (!connect.enabled()) {
            hold(p.id(), connect.disabledReason());
            return "held";
        }
        if (!p.mode().equals(connect.mode())) {
            hold(p.id(), "This share was allocated in " + p.mode() + " mode and the server now runs in " + connect.mode() + " mode.");
            return "held";
        }
        if (!ignoreBackoff && "FAILED".equals(p.status()) && p.nextAttempt() != null && p.nextAttempt().isAfter(now)) return "skipped";
        String account = readyAccount(p.moderatorId(), p.provider(), p.mode());
        if (account == null) {
            hold(p.id(), HELD_CONNECT);
            return "held";
        }
        StripeGateway gw = connect.gateway();
        String group = "howlers-payout-" + p.id();
        if (p.failures() > 0) {
            // An earlier attempt failed or its answer was lost: if Stripe made the transfer anyway, record that one.
            try {
                Optional<Transfer> made = gw.transfersInGroup(group).stream()
                        .filter(t -> Long.toString(p.id()).equals(t.metadata().get(META_PAYOUT)) && account.equals(t.destination())).findFirst();
                if (made.isPresent()) {
                    recordPaid(p, account, made.get());
                    return "paid";
                }
            } catch (RuntimeException e) {
                return failed(p, now, true, "Stripe didn't answer while checking an earlier attempt; trying again shortly.");
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(META_PAYOUT, Long.toString(p.id()));
        meta.put("howlers_pool_id", Long.toString(p.poolId()));
        meta.put("howlers_allocation_id", Long.toString(p.allocationId()));
        meta.put("howlers_moderator_id", Long.toString(p.moderatorId()));
        Transfer t;
        try {
            t = gw.createTransfer(new TransferRequest(p.amount(), p.currency(), account, group,
                    "Howlers moderator payout, pool #" + p.poolId() + (Money.MODE_TEST.equals(p.mode()) ? " (test mode)" : ""), meta,
                    "howlers-payout-" + p.id() + "-" + p.attempt()));
        } catch (StripeGatewayException e) {
            log.warn("Transfer for payout {} failed (unknown outcome: {}): {}", p.id(), e.outcomeUnknown(), e.getMessage());
            return failed(p, now, e.outcomeUnknown(), e.outcomeUnknown() ? "Stripe didn't answer; checking again shortly."
                    : "Stripe refused the transfer" + (e.code() == null ? "" : " (" + e.code() + ")") + ". It is retried automatically.");
        } catch (RuntimeException e) {
            log.warn("Transfer for payout {} failed: {}", p.id(), e.toString());
            return failed(p, now, true, "The transfer couldn't be confirmed; checking again shortly.");
        }
        if (t == null || t.id() == null) return failed(p, now, true, "Stripe returned no transfer; checking again shortly.");
        recordPaid(p, account, t);
        return "paid";
    }

    private void hold(long id, String reason) {
        jdbc.sql("UPDATE funding_payouts SET status_reason = ?, updated_at = now() WHERE id = ? AND status = 'HELD'").params(trim(reason, 300), id)
                .update();
    }

    /**
     * Records a failed attempt. A definitive refusal moves the attempt on (a new idempotency key next time); an unknown
     * outcome keeps it, so the retry returns whatever Stripe did the first time.
     */
    private String failed(Row p, Instant now, boolean unknown, String reason) {
        int failures = p.failures() + 1;
        Duration wait = unknown ? Duration.ofMinutes(5) : Duration.ofMinutes(Math.min(24 * 60, 30L << Math.min(failures - 1, 6)));
        jdbc.sql("""
                        UPDATE funding_payouts SET status = 'FAILED', status_reason = ?, failures = ?, attempt = ?, next_attempt_at = ?, updated_at = now()
                        WHERE id = ?""")
                .params(trim(reason, 300), failures, unknown ? p.attempt() : p.attempt() + 1, Timestamp.from(now.plus(wait)), p.id()).update();
        if (failures >= REVIEW_AFTER_FAILURES) {
            flag("TRANSFER_FAILING", "failing:" + p.id(), p.poolId(), p.id(), p.amount(), p.currency(), p.mode(),
                    "The transfer for this payout has failed " + failures + " times. Last reason: " + reason
                            + " In test mode this is usually an empty platform balance (pay with the 4000 0000 0000 0077 test card).");
        }
        return "failed";
    }

    private void recordPaid(Row p, String account, Transfer t) {
        jdbc.sql("""
                        UPDATE funding_payouts SET status = 'PAID', transfer_id = ?, connected_account = ?, transferred_minor = ?, status_reason = NULL,
                          paid_at = now(), next_attempt_at = NULL, updated_at = now()
                        WHERE id = ?""")
                .params(t.id(), account, p.amount(), p.id()).update();
        ledger(p, "PAYOUT", "moderator:" + p.moderatorId(), "connected:" + p.moderatorId(), p.amount(), "transfer:" + t.id());
        if (t.amountReversed() > 0) applyReversal(p.id(), t);
    }

    private void ledger(Row p, String type, String debit, String credit, long amount, String ref) {
        jdbc.sql("""
                        INSERT INTO funding_ledger (pool_id, entry_type, debit_account, credit_account, amount_minor, currency, payout_id, external_ref,
                          demo, mode)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (external_ref) DO NOTHING""")
                .params(p.poolId(), type, debit, credit, amount, p.currency(), p.id(), ref, !Money.MODE_LIVE.equals(p.mode()), p.mode()).update();
    }

    // ------------------------------------------------------------------ webhooks: transfer.created / .updated / .reversed

    /**
     * A transfer event. Records a transfer whose API response was lost (matched by Howlers' payout id in the metadata,
     * the destination and the amount), and books any reversal. Replays change nothing.
     */
    String onTransferEvent(Map<String, Object> object) {
        Transfer t = transfer(object);
        if (t.id() == null) return "no transfer id";
        Row p = row("transfer_id = ?", t.id(), "FOR UPDATE").orElse(null);
        if (p == null) {
            String ref = t.metadata().get(META_PAYOUT);
            if (ref == null || !ref.matches("\\d{1,18}")) return "not a Howlers payout";
            p = row("id = ?", Long.parseLong(ref), "FOR UPDATE").orElse(null);
            if (p == null) return "unknown payout";
            if (p.transferId() != null) return "payout already has another transfer";
            String account = jdbc.sql("SELECT account_id FROM funding_connected_accounts WHERE user_id = ? AND provider = ? AND mode = ?")
                    .params(p.moderatorId(), p.provider(), p.mode()).query(String.class).optional().orElse(null);
            if (account == null || !account.equals(t.destination()) || t.amountMinor() != p.amount()
                    || (t.currency() != null && !t.currency().equalsIgnoreCase(p.currency()))) {
                log.error("Transfer {} names payout {} but doesn't match it; not recorded", t.id(), p.id());
                return "transfer does not match the payout";
            }
            recordPaid(p, account, t);
            return t.amountReversed() > 0 ? "transfer and reversal recorded" : "transfer recorded";
        }
        if (t.amountReversed() > p.reversed()) {
            applyReversal(p.id(), t);
            return "reversal recorded";
        }
        return "already recorded";
    }

    /**
     * {@code amount_reversed} is cumulative: the difference from what's already booked becomes a PAYOUT_REVERSAL row
     * (connected account back to the moderator's allocation), keyed by the cumulative amount so each state is booked once.
     * A reversed payout is never re-sent automatically: an admin reviews it.
     */
    private void applyReversal(long payoutId, Transfer t) {
        Row p = row("id = ?", payoutId, "FOR UPDATE").orElseThrow();
        long cumulative = Math.min(t.amountReversed(), p.transferred());
        long delta = cumulative - p.reversed();
        if (delta <= 0) return;
        ledger(p, "PAYOUT_REVERSAL", "connected:" + p.moderatorId(), "moderator:" + p.moderatorId(), delta, "reversal:" + t.id() + ":" + cumulative);
        boolean full = cumulative >= p.transferred();
        jdbc.sql("UPDATE funding_payouts SET reversed_minor = ?, status = ?, status_reason = ?, updated_at = now() WHERE id = ?")
                .params(cumulative, full ? "REVERSED" : "PAID", full ? "The transfer was reversed in Stripe. An admin reviews it before anything is re-sent."
                        : "Part of the transfer was reversed in Stripe. An admin reviews it.", p.id()).update();
        flag("TRANSFER_REVERSED", "reversal:" + t.id() + ":" + cumulative, p.poolId(), p.id(), delta, p.currency(), p.mode(),
                "Stripe reversed " + fmt(delta, p.currency(), p.mode()) + " of transfer " + t.id() + " (payout #" + p.id()
                        + "). The amount is held again for the moderator; nothing is re-sent automatically.");
    }

    static Transfer transfer(Map<String, Object> o) {
        Long amount = StripePaymentProvider.num(o.get("amount")), reversed = StripePaymentProvider.num(o.get("amount_reversed"));
        return new Transfer(StripePaymentProvider.str(o.get("id")), amount == null ? 0 : amount, reversed == null ? 0 : reversed,
                StripePaymentProvider.str(o.get("currency")), StripePaymentProvider.str(o.get("destination")),
                StripePaymentProvider.str(o.get("transfer_group")), StripePaymentProvider.metadata(o.get("metadata")));
    }

    // ------------------------------------------------------------------ refunds after payout, review flags

    /**
     * A refund was recorded. If its pool is closed and has payouts, nothing is clawed back automatically: an admin decides.
     */
    void flagRefund(long refundId) {
        record R(long poolId, long amount, String currency, String mode, String status) {}
        R r = jdbc.sql("""
                        SELECT r.pool_id, r.amount_minor, r.currency, r.mode, p.status FROM funding_refunds r JOIN funding_pools p ON p.id = r.pool_id
                        WHERE r.id = ?""")
                .param(refundId).query((rs, i) -> new R(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .optional().orElse(null);
        if (r == null || !"CLOSED".equals(r.status())) return;
        record S(long count, long sent) {}
        S s = jdbc.sql("SELECT count(*), coalesce(sum(transferred_minor - reversed_minor), 0) FROM funding_payouts WHERE pool_id = ?")
                .param(r.poolId()).query((rs, i) -> new S(rs.getLong(1), rs.getLong(2))).single();
        if (s.count() == 0) return;
        String refunded = fmt(r.amount(), r.currency(), r.mode());
        String detail = s.sent() > 0
                ? "A contribution to pool #" + r.poolId() + " was refunded (" + refunded + ") after " + fmt(s.sent(), r.currency(), r.mode())
                + " was paid out to moderators. Nothing is clawed back automatically: reverse a transfer in the Stripe dashboard, or absorb it."
                : "A contribution to pool #" + r.poolId() + " was refunded (" + refunded + ") after the pool closed, while moderator shares are still "
                + "held. Held shares are still paid in full when moderators connect, unless you intervene.";
        flag("REFUND_AFTER_PAYOUT", "refund:" + refundId, r.poolId(), null, r.amount(), r.currency(), r.mode(), detail);
    }

    private void flag(String kind, String ref, long poolId, Long payoutId, long amount, String currency, String mode, String detail) {
        jdbc.sql("""
                        INSERT INTO funding_review_flags (kind, ref, pool_id, payout_id, amount_minor, currency, mode, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (ref) DO NOTHING""")
                .params(kind, trim(ref, 120), poolId, payoutId, amount, currency, mode, trim(detail, 500)).update();
    }

    // ------------------------------------------------------------------ reading

    /** The label shown for a payout. Spells out test mode and what the moderator has to do. */
    static String label(String status, String mode, long reversed, boolean connectEnabled, String accountStatus) {
        boolean test = !Money.MODE_LIVE.equals(mode);
        return switch (status) {
            case "PAID" -> (test ? "Paid out (test mode)" : "Paid out") + (reversed > 0 ? ", partly reversed" : "");
            case "REVERSED" -> "Reversed: held for review";
            case "FAILED" -> "Held: transfer failed, retrying";
            default -> !connectEnabled ? "Held: payouts not enabled yet"
                    : "ENABLED".equals(accountStatus) ? "Held: transfer queued" : "Held: connect your account";
        };
    }

    /** Payout state per moderator for one pool, for the public transparency report (no account ids). */
    Map<Long, AllocationPayout> forPool(long poolId) {
        Map<Long, AllocationPayout> out = new HashMap<>();
        jdbc.sql("""
                        SELECT p.moderator_id, p.status, p.mode, p.reversed_minor, p.paid_at, a.status FROM funding_payouts p
                        LEFT JOIN funding_connected_accounts a ON a.user_id = p.moderator_id AND a.provider = p.provider AND a.mode = p.mode
                        WHERE p.pool_id = ?""")
                .param(poolId)
                .query((rs, i) -> out.put(rs.getLong(1), new AllocationPayout(rs.getString(2),
                        label(rs.getString(2), rs.getString(3), rs.getLong(4), connect.enabled(), rs.getString(6)), FundingService.ts(rs, 5))))
                .list();
        return out;
    }

    /**
     * allocated = transferred (net of reversals) + held, with the transferred part taken from the PAYOUT ledger rows and
     * cross-checked against the payout rows; every positive allocation has exactly one payout.
     */
    PayoutReconciliation reconcile(long poolId, String provider, String mode, String status, String currency) {
        long allocated = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_allocations WHERE pool_id = ?").param(poolId)
                .query(Long.class).single();
        if (!paysOut(provider, mode) || !"CLOSED".equals(status)) {
            Money zero = Money.of(0, currency, mode);
            return new PayoutReconciliation(false, Money.of(allocated, currency, mode), zero, zero, zero, true);
        }
        long positive = jdbc.sql("SELECT count(*) FROM funding_allocations WHERE pool_id = ? AND amount_minor > 0").param(poolId)
                .query(Long.class).single();
        record P(long count, long amount, long transferred, long reversed) {}
        P p = jdbc.sql("""
                        SELECT count(*), coalesce(sum(amount_minor), 0), coalesce(sum(transferred_minor), 0), coalesce(sum(reversed_minor), 0)
                        FROM funding_payouts WHERE pool_id = ?""")
                .param(poolId).query((rs, i) -> new P(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))).single();
        long out = ledgerSum(poolId, "PAYOUT"), back = ledgerSum(poolId, "PAYOUT_REVERSAL");
        long net = out - back;
        long held = p.amount() - (p.transferred() - p.reversed());
        boolean ok = positive == p.count() && allocated == p.amount() && out == p.transferred() && back == p.reversed() && allocated == net + held;
        return new PayoutReconciliation(true, Money.of(allocated, currency, mode), Money.of(net, currency, mode), Money.of(held, currency, mode),
                Money.of(back, currency, mode), ok);
    }

    private long ledgerSum(long poolId, String type) {
        return jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM funding_ledger WHERE pool_id = ? AND entry_type = ?").params(poolId, type)
                .query(Long.class).single();
    }

    /** "12.34 USD (test mode)", with integer arithmetic only. */
    static String fmt(long minor, String currency, String mode) {
        int exp = Money.CURRENCIES.getOrDefault(currency, 2);
        long unit = 1;
        for (int i = 0; i < exp; i++) unit *= 10;
        String digits = exp == 0 ? Long.toString(minor) : (minor / unit) + "." + String.format("%0" + exp + "d", Math.abs(minor % unit));
        return digits + " " + currency + (Money.MODE_LIVE.equals(mode) ? "" : " (" + mode + " mode)");
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

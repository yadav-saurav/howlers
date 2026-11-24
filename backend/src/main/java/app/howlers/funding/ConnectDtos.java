package app.howlers.funding;

import app.howlers.funding.FundingDtos.PoolRef;
import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Stripe Connect payouts to moderators (D-046, D-047). No bank or identity detail ever appears here: Stripe holds those. */
public final class ConnectDtos {
    private ConnectDtos() {}

    /**
     * The moderator's connected account. {@code status}: NOT_STARTED (no account yet), PENDING (onboarding not finished, or
     * Stripe is verifying), REQUIREMENTS_DUE (Stripe needs more information), ENABLED (payouts enabled). {@code accountHint}
     * is the account id shortened to its last four characters.
     */
    public record ConnectAccount(String status, String accountHint, boolean detailsSubmitted, boolean payoutsEnabled, boolean transfersActive,
                                 int requirementsDue, String disabledReason, String message, String lastPayoutStatus, Instant lastPayoutAt,
                                 Instant syncedAt) {}

    /**
     * One allocation's payout. {@code status}: HELD (waiting for a ready account, or for Connect to be enabled), FAILED
     * (Stripe refused the transfer; retried automatically), PAID, REVERSED (the transfer was reversed in Stripe; held for an
     * admin). {@code label} is the text to show, e.g. "Paid out (test mode)" or "Held: connect your account".
     */
    public record Payout(long id, PoolRef pool, Money amount, String status, String label, String reason, Money transferred, Money reversed,
                         Instant paidAt, Instant createdAt) {}

    /**
     * Everything the moderator's payout page needs. {@code eligible}: the user moderates a community or has payouts, so
     * onboarding is offered. {@code held} and {@code paid} are totals per currency.
     */
    public record MyPayouts(boolean connectEnabled, String mode, String label, String notice, boolean eligible, ConnectAccount account,
                            List<Payout> payouts, List<Money> held, List<Money> paid) {}

    /** A single-use Stripe-hosted onboarding page. Send the browser there; Stripe returns it to Howlers. */
    public record OnboardingLink(String url, long expiresAt) {}

    /** The payout state of one allocation in a transparency report (public: no account ids). */
    public record AllocationPayout(String status, String label, Instant paidAt) {}

    /**
     * Payout reconciliation for a closed Stripe pool: allocated = transferred (net of reversals) + held, checked against the
     * PAYOUT ledger rows. {@code applicable} is false for demo pools, whose allocations are never paid out.
     */
    public record PayoutReconciliation(boolean applicable, Money allocated, Money transferred, Money held, Money reversed, boolean reconciles) {}

    // ---------------------------------------------------------------- admin

    public record WebhookSeen(String type, String outcome, Instant receivedAt) {}

    /**
     * Stripe set-up as the server sees it, for the admin panel. Secrets are never included, only whether they're set.
     * {@code webhookUrl} is the endpoint to enter in the Stripe dashboard; {@code platformEvents} and {@code connectEvents}
     * are the events to select for the platform endpoint and the connected-accounts endpoint.
     */
    public record StripeStatus(boolean fundingEnabled, String provider, String mode, String label, boolean secretKeySet,
                               boolean webhookSecretSet, boolean connectWebhookSecretSet, WebhookSeen lastWebhook, long webhooksLast24h,
                               boolean connectEnabled, String connectNote, String connectCountry, String webhookUrl,
                               List<String> platformEvents, List<String> connectEvents, long connectedAccounts, long accountsEnabled,
                               long payoutsHeld, long payoutsFailed, long payoutsPaid, long payoutsReversed, long openReviews) {}

    /** Something an admin must handle by hand (no automatic claw-back). */
    public record ReviewFlag(long id, String kind, PoolRef pool, Long payoutId, UserSummary moderator, Money amount, String detail,
                             Instant createdAt, Instant resolvedAt, UserSummary resolvedBy, String resolutionNote) {}

    public record ResolveReviewRequest(@NotBlank @Size(min = 5, max = 500) String note) {}

    /** Result of running the payout job by hand. */
    public record PayoutRun(int attempted, int paid, int stillHeld, int failed) {}
}

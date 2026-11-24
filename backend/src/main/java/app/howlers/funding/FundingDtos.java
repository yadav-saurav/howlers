package app.howlers.funding;

import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class FundingDtos {
    private FundingDtos() {}

    public record CurrencyInfo(String code, int exponent) {}

    /**
     * What the server can do. {@code demo} is true when no real money moves (demo and Stripe test mode); {@code mode} is
     * demo | test | live; {@code label} comes from the provider; {@code checkout} is "instant" (demo) or "hosted" (Stripe
     * Checkout: the browser is redirected and the contribution is confirmed afterwards); {@code payouts} says allocations
     * are recorded obligations only.
     */
    public record FundingStatus(boolean enabled, String provider, boolean demo, String mode, String label, String notice,
                                String checkout, String payouts, List<CurrencyInfo> currencies) {}

    public record Policy(String policy, int activityCap, UserSummary updatedBy, Instant updatedAt) {}

    public record PoolSummary(long id, CommunityRef community, String title, String purpose, Money goal, Money raised,
                              long contributorCount, Instant periodStart, Instant periodEnd, String status, String provider,
                              boolean demo, String mode, UserSummary createdBy, String closedPolicy, Instant closedAt, Instant createdAt) {}

    public record CommunityViewer(boolean canContribute, boolean canCreatePool, boolean canSetPolicy, boolean canClose) {}

    public record CommunityFunding(FundingStatus status, Policy policy, List<PoolSummary> pools, CommunityViewer viewer) {}

    /** A confirmed contribution. {@code refunded} is what the provider has refunded of it so far (reversed in the ledger). */
    public record Contribution(long id, PoolRef pool, String kind, Money amount, boolean showName, Long subscriptionId,
                               Integer cycle, String chargeId, String provider, Money refunded, Instant createdAt) {}

    public record PoolRef(long id, String title, CommunityRef community, String status) {}

    /** {@code billedBy}: "howlers" (the demo job charges cycles) or "provider" (Stripe bills each month itself). */
    public record SubscriptionDto(long id, PoolRef pool, Money amount, boolean showName, String status, int cyclesCharged,
                                  Instant nextChargeAt, Instant createdAt, Instant cancelledAt, String cancelledBy, String provider,
                                  String billedBy, Instant paymentFailedAt) {}

    /**
     * Demo: {@code contribution} (and {@code subscription} when monthly) are recorded at once. Hosted checkout:
     * {@code checkout} says where to send the browser; nothing counts until it's confirmed.
     */
    public record ContributeResult(Contribution contribution, SubscriptionDto subscription, CheckoutStart checkout) {}

    /** A hosted checkout to redirect to. {@code url} is the provider's page (Stripe Checkout). */
    public record CheckoutStart(long pendingId, String url, String provider, String mode) {}

    /**
     * A pending contribution and where it stands. PENDING: not confirmed yet (keep polling, or wait for the webhook).
     * CONFIRMED: recorded, {@code contribution} (and {@code subscription}) are set. CANCELLED / EXPIRED / FAILED: nothing
     * was recorded; {@code reason} says why.
     */
    public record CheckoutStatus(long pendingId, String status, String reason, PoolRef pool, String kind, Money amount,
                                 Contribution contribution, SubscriptionDto subscription) {}

    public record ConfirmCheckoutRequest(@NotBlank @Size(max = 255) String sessionId) {}

    public record MyFunding(FundingStatus status, List<Contribution> contributions, List<SubscriptionDto> subscriptions,
                            List<PoolSummary> openPools) {}

    /** {@code payout} is set for closed Stripe pools (Connect payouts, D-046): whether the share was paid out or is held. */
    public record Allocation(UserSummary moderator, String policy, Money amount, Integer activityScore, Integer reportsResolved,
                             Integer appealsHandled, String note, Instant createdAt, ConnectDtos.AllocationPayout payout) {}

    public record LedgerEntry(long id, String entryType, String debitAccount, String debitLabel, String creditAccount,
                              String creditLabel, Money amount, Instant createdAt) {}

    /**
     * Independent sums. {@code refundsTotal} are reversals (REFUND ledger rows, pool to contributors). {@code shortfall}
     * is what was refunded after the pool closed: allocations are obligations, so a late refund leaves them uncovered.
     */
    public record Reconciliation(Money contributionsTotal, Money ledgerIn, Money ledgerOut, Money allocationsTotal,
                                 Money poolBalance, boolean reconciles, Money refundsTotal, Money shortfall) {}

    public record TransparencyReport(PoolSummary pool, Money raised, long contributionCount, long contributorCount,
                                     long anonymousContributors, List<UserSummary> namedContributors, Money allocated,
                                     List<Allocation> allocations, List<LedgerEntry> ledger, boolean ledgerTruncated,
                                     Reconciliation reconciliation, String closedNote, Money refunded, long refundCount,
                                     ConnectDtos.PayoutReconciliation payouts) {}

    /** What closing the pool now would allocate. Nothing is written. */
    public record AllocationPreview(String policy, Money total, List<Allocation> allocations, String note,
                                    Instant windowStart, Instant windowEnd) {}

    // ---------------------------------------------------------------- requests

    public record CreatePoolRequest(@NotBlank @Size(max = 120) String title, @NotBlank @Size(min = 10, max = 2000) String purpose,
                                    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency, @NotNull @Positive Long goalMinor,
                                    Instant periodStart, @NotNull Instant periodEnd) {}

    public record ContributeRequest(@NotNull @Positive Long amountMinor, boolean recurring, boolean showName) {}

    public record PolicyRequest(@NotNull @Pattern(regexp = "EQUAL|ACTIVITY|MANUAL") String policy, @Min(1) @Max(1000) Integer activityCap) {}

    public record ManualAllocation(@NotBlank @Size(max = 21) String username, @NotNull @PositiveOrZero Long amountMinor) {}

    /** Closing a pool. {@code allocations} and {@code note} are required when the community's policy is MANUAL. */
    public record CloseRequest(@Valid @Size(max = 100) List<ManualAllocation> allocations, @Size(max = 1000) String note) {}

    public record WebhookResult(String status, String eventId) {}
}

import type { CommunityRef, UserSummary } from './api';
import type { AllocationPayout, PayoutReconciliation } from './payouts';

/** demo: simulated; test: Stripe test mode; live: real money. */
export type FundingMode = 'demo' | 'test' | 'live';

/**
 * Integer minor units (cents) of an ISO 4217 currency. Never a float. `demo` is true whenever no real money moved
 * (demo and test mode). `label` comes from the server ("DEMO, no real money", "TEST MODE: no real money", or null for live).
 */
export interface Money {
  amountMinor: number;
  currency: string;
  demo: boolean;
  mode: FundingMode;
  label: string | null;
}

export type PoolStatus = 'OPEN' | 'AWAITING_ALLOCATION' | 'CLOSED';
export type AllocationPolicy = 'EQUAL' | 'ACTIVITY' | 'MANUAL';

export interface FundingStatus {
  enabled: boolean;
  provider: string | null;
  demo: boolean;
  mode: FundingMode;
  label: string | null;
  notice: string | null;
  /** instant: recorded at once (demo). hosted: the browser goes to Stripe Checkout and the contribution is confirmed afterwards. */
  checkout: 'instant' | 'hosted' | null;
  payouts: string;
  currencies: { code: string; exponent: number }[];
}

export interface FundingPolicy {
  policy: AllocationPolicy;
  activityCap: number;
  updatedBy: UserSummary | null;
  updatedAt: string | null;
}

export interface PoolSummary {
  id: number;
  community: CommunityRef;
  title: string;
  purpose: string;
  goal: Money;
  raised: Money;
  contributorCount: number;
  periodStart: string;
  periodEnd: string;
  status: PoolStatus;
  provider: string;
  demo: boolean;
  mode: FundingMode;
  createdBy: UserSummary | null;
  closedPolicy: AllocationPolicy | null;
  closedAt: string | null;
  createdAt: string;
}

export interface CommunityFunding {
  status: FundingStatus;
  policy: FundingPolicy;
  pools: PoolSummary[];
  viewer: { canContribute: boolean; canCreatePool: boolean; canSetPolicy: boolean; canClose: boolean };
}

export interface PoolRef {
  id: number;
  title: string;
  community: CommunityRef;
  status: PoolStatus;
}

export interface Contribution {
  id: number;
  pool: PoolRef;
  kind: 'ONE_TIME' | 'RECURRING';
  amount: Money;
  showName: boolean;
  subscriptionId: number | null;
  cycle: number | null;
  chargeId: string;
  provider: string;
  /** Refunded so far by the provider; reversed in the ledger with new rows. */
  refunded: Money;
  createdAt: string;
}

export interface FundingSubscription {
  id: number;
  pool: PoolRef;
  amount: Money;
  showName: boolean;
  status: 'ACTIVE' | 'CANCELLED' | 'ENDED';
  cyclesCharged: number;
  nextChargeAt: string | null;
  createdAt: string;
  cancelledAt: string | null;
  cancelledBy: 'USER' | 'PROVIDER' | 'SYSTEM' | null;
  provider: string;
  /** howlers: the demo job records each month; provider: Stripe bills each month and the server records paid invoices. */
  billedBy: 'howlers' | 'provider';
  paymentFailedAt: string | null;
}

export interface CheckoutStart {
  pendingId: number;
  url: string;
  provider: string;
  mode: FundingMode;
}

export interface ContributeResult {
  contribution: Contribution | null;
  subscription: FundingSubscription | null;
  /** Set for hosted checkout: send the browser to `url`. */
  checkout: CheckoutStart | null;
}

export type CheckoutState = 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'FAILED';

export interface CheckoutStatus {
  pendingId: number;
  status: CheckoutState;
  reason: string | null;
  pool: PoolRef;
  kind: 'ONE_TIME' | 'RECURRING';
  amount: Money;
  contribution: Contribution | null;
  subscription: FundingSubscription | null;
}

export interface MyFunding {
  status: FundingStatus;
  contributions: Contribution[];
  subscriptions: FundingSubscription[];
  openPools: PoolSummary[];
}

export interface FundingAllocation {
  moderator: UserSummary | null;
  policy: AllocationPolicy;
  amount: Money;
  activityScore: number | null;
  reportsResolved: number | null;
  appealsHandled: number | null;
  note: string | null;
  createdAt: string | null;
  /** Closed Stripe pools only: whether this share was paid out through Stripe Connect or is held. */
  payout?: AllocationPayout | null;
}

export interface LedgerEntry {
  id: number;
  entryType: 'CONTRIBUTION' | 'ALLOCATION' | 'REFUND' | 'PAYOUT' | 'PAYOUT_REVERSAL';
  debitAccount: string;
  debitLabel: string;
  creditAccount: string;
  creditLabel: string;
  amount: Money;
  createdAt: string;
}

export interface Reconciliation {
  contributionsTotal: Money;
  ledgerIn: Money;
  ledgerOut: Money;
  allocationsTotal: Money;
  poolBalance: Money;
  reconciles: boolean;
  refundsTotal: Money;
  /** Refunded after the pool closed: allocations (obligations) no longer fully covered. */
  shortfall: Money;
}

export interface TransparencyReport {
  pool: PoolSummary;
  raised: Money;
  contributionCount: number;
  contributorCount: number;
  anonymousContributors: number;
  namedContributors: UserSummary[];
  allocated: Money;
  allocations: FundingAllocation[];
  ledger: LedgerEntry[];
  ledgerTruncated: boolean;
  reconciliation: Reconciliation;
  closedNote: string | null;
  refunded: Money;
  refundCount: number;
  /** allocated = transferred + held, for closed Stripe pools (Connect payouts). */
  payouts?: PayoutReconciliation;
}

export interface AllocationPreview {
  policy: AllocationPolicy;
  total: Money;
  allocations: FundingAllocation[];
  note: string | null;
  windowStart: string;
  windowEnd: string;
}

export interface CreatePoolBody {
  title: string;
  purpose: string;
  currency: string;
  goalMinor: number;
  periodEnd: string;
}

export interface ContributeBody {
  amountMinor: number;
  recurring: boolean;
  showName: boolean;
}

export interface CloseBody {
  allocations?: { username: string; amountMinor: number }[];
  note?: string;
}

import type { UserSummary } from './api';
import type { FundingMode, Money, PoolRef } from './funding';

/** Stripe Connect payouts to moderators (D-046, D-047). No bank or identity details ever reach Howlers. */
export type ConnectStatus = 'NOT_STARTED' | 'PENDING' | 'REQUIREMENTS_DUE' | 'ENABLED';
export type PayoutStatus = 'HELD' | 'FAILED' | 'PAID' | 'REVERSED';

export interface ConnectAccount {
  status: ConnectStatus;
  /** "acct_…1234" */
  accountHint: string | null;
  detailsSubmitted: boolean;
  payoutsEnabled: boolean;
  transfersActive: boolean;
  requirementsDue: number;
  disabledReason: string | null;
  message: string;
  lastPayoutStatus: string | null;
  lastPayoutAt: string | null;
  syncedAt: string | null;
}

export interface Payout {
  id: number;
  pool: PoolRef;
  amount: Money;
  status: PayoutStatus;
  /** e.g. "Paid out (test mode)", "Held: connect your account" */
  label: string;
  reason: string | null;
  transferred: Money;
  reversed: Money;
  paidAt: string | null;
  createdAt: string;
}

export interface MyPayouts {
  connectEnabled: boolean;
  mode: FundingMode | null;
  label: string | null;
  notice: string;
  /** Moderates a community or has payouts, so onboarding is offered. */
  eligible: boolean;
  account: ConnectAccount;
  payouts: Payout[];
  held: Money[];
  paid: Money[];
}

export interface OnboardingLink {
  url: string;
  expiresAt: number;
}

export interface AllocationPayout {
  status: PayoutStatus;
  label: string;
  paidAt: string | null;
}

export interface PayoutReconciliation {
  applicable: boolean;
  allocated: Money;
  transferred: Money;
  held: Money;
  reversed: Money;
  reconciles: boolean;
}

export interface WebhookSeen {
  type: string;
  outcome: string;
  receivedAt: string;
}

/** The admin Stripe panel. Never contains a secret, only whether each is set. */
export interface StripeStatus {
  fundingEnabled: boolean;
  provider: string | null;
  mode: FundingMode | null;
  label: string | null;
  secretKeySet: boolean;
  webhookSecretSet: boolean;
  connectWebhookSecretSet: boolean;
  lastWebhook: WebhookSeen | null;
  webhooksLast24h: number;
  connectEnabled: boolean;
  connectNote: string | null;
  connectCountry: string | null;
  webhookUrl: string;
  platformEvents: string[];
  connectEvents: string[];
  connectedAccounts: number;
  accountsEnabled: number;
  payoutsHeld: number;
  payoutsFailed: number;
  payoutsPaid: number;
  payoutsReversed: number;
  openReviews: number;
}

export type ReviewKind = 'REFUND_AFTER_PAYOUT' | 'TRANSFER_REVERSED' | 'TRANSFER_FAILING';

export interface ReviewFlag {
  id: number;
  kind: ReviewKind;
  pool: PoolRef;
  payoutId: number | null;
  moderator: UserSummary | null;
  amount: Money;
  detail: string;
  createdAt: string;
  resolvedAt: string | null;
  resolvedBy: UserSummary | null;
  resolutionNote: string | null;
}

export interface PayoutRun {
  attempted: number;
  paid: number;
  stillHeld: number;
  failed: number;
}

import type { ConnectStatus, PayoutStatus } from '@/types/payouts';

export type Tone = 'neutral' | 'accent' | 'danger' | 'warning' | 'info';

/** Headline, tone and the main button for each connected-account state. */
export function connectStatusInfo(status: ConnectStatus): { title: string; tone: Tone; action: string | null } {
  switch (status) {
    case 'ENABLED':
      return { title: 'Payouts enabled', tone: 'accent', action: null };
    case 'REQUIREMENTS_DUE':
      return { title: 'Stripe needs more information', tone: 'warning', action: 'Resume onboarding' };
    case 'PENDING':
      return { title: 'Onboarding in progress', tone: 'info', action: 'Resume onboarding' };
    default:
      return { title: 'No payout account yet', tone: 'neutral', action: 'Connect with Stripe' };
  }
}

export function payoutTone(status: PayoutStatus): Tone {
  switch (status) {
    case 'PAID':
      return 'accent';
    case 'FAILED':
      return 'danger';
    case 'REVERSED':
      return 'warning';
    default:
      return 'neutral';
  }
}

/**
 * What to do when Stripe sends the moderator back: `return` (onboarding finished or left) re-reads the account;
 * `refresh` (the single-use link expired or was reused) asks for a fresh link. Anything else is ignored.
 */
export function parseConnectReturn(params: URLSearchParams): 'return' | 'refresh' | null {
  const v = params.get('connect');
  return v === 'return' || v === 'refresh' ? v : null;
}

/** Only ever send the browser to Stripe's own onboarding host. */
export function isStripeOnboardingUrl(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' && (u.hostname === 'connect.stripe.com' || u.hostname.endsWith('.stripe.com'));
  } catch {
    return false;
  }
}

export const REVIEW_KIND_LABELS: Record<string, string> = {
  REFUND_AFTER_PAYOUT: 'Refund after payout',
  TRANSFER_REVERSED: 'Transfer reversed',
  TRANSFER_FAILING: 'Transfer keeps failing',
};

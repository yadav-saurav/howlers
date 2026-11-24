import { ApiError } from '@/services/apiError';

/** What the verify-email page shows (D-038). */
export type VerifyOutcome = 'verified' | 'already-verified' | 'expired' | 'invalid' | 'rate-limited' | 'error';

export type VerifyEmailResult = { status: 'VERIFIED' | 'ALREADY_VERIFIED' };

export function outcomeFromResult(r: VerifyEmailResult | undefined): VerifyOutcome {
  return r?.status === 'ALREADY_VERIFIED' ? 'already-verified' : 'verified';
}

export function outcomeFromError(e: unknown): VerifyOutcome {
  if (!(e instanceof ApiError)) return 'error';
  if (e.code === 'LINK_EXPIRED') return 'expired';
  if (e.code === 'LINK_INVALID') return 'invalid';
  if (e.status === 429) return 'rate-limited';
  return 'error';
}

export const outcomeCopy: Record<VerifyOutcome, { title: string; body: string }> = {
  verified: { title: 'Email verified', body: 'Thanks, your email address is confirmed.' },
  'already-verified': { title: 'Already verified', body: 'This email address was confirmed earlier. Nothing else to do.' },
  expired: {
    title: 'Link expired',
    body: 'This link has expired or was replaced by a newer one. Request a new verification email below.',
  },
  invalid: {
    title: 'Link not recognised',
    body: 'This verification link isn’t valid. Check that you opened the whole link from the latest email.',
  },
  'rate-limited': { title: 'Too many attempts', body: 'Please wait a little and open the link again.' },
  error: { title: 'Verification failed', body: 'Something went wrong. Try the link again in a moment.' },
};

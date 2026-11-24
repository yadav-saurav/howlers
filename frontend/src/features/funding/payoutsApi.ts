import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import type { MyPayouts, OnboardingLink, PayoutRun, ReviewFlag, StripeStatus } from '@/types/payouts';
import { fundingKeys } from './api';

/** Stripe Connect payouts (D-046, D-047). */
export const payoutsApi = {
  mine: () => http.get<MyPayouts>('/me/funding/payouts'),
  /** Returns a single-use Stripe-hosted onboarding URL for the signed-in moderator. */
  onboarding: () => http.post<OnboardingLink>('/me/funding/connect/onboarding'),
  refresh: () => http.post<MyPayouts>('/me/funding/connect/refresh'),
  stripeStatus: () => http.get<StripeStatus>('/admin/funding/stripe'),
  reviews: (open: boolean) => http.get<ReviewFlag[]>('/admin/funding/reviews', { open }),
  resolve: (id: number, note: string) => http.post<ReviewFlag>(`/admin/funding/reviews/${id}/resolve`, { note }),
  runPayouts: () => http.post<PayoutRun>('/admin/funding/payouts/run'),
};

export const payoutKeys = {
  mine: [...fundingKeys.all, 'payouts'] as const,
  stripe: ['admin', 'funding', 'stripe'] as const,
  reviews: (open: boolean) => ['admin', 'funding', 'reviews', open] as const,
};

export const useMyPayouts = () => useQuery({ queryKey: payoutKeys.mine, queryFn: payoutsApi.mine });
export const useStripeStatus = () => useQuery({ queryKey: payoutKeys.stripe, queryFn: payoutsApi.stripeStatus });
export const useReviewFlags = (open: boolean) => useQuery({ queryKey: payoutKeys.reviews(open), queryFn: () => payoutsApi.reviews(open) });

/** Admin payout actions refresh the panel, the review queue and every funding view. */
export function useAdminPayoutMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['admin', 'funding'] });
      void qc.invalidateQueries({ queryKey: fundingKeys.all });
    },
  });
}

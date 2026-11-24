import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import type {
  AllocationPolicy,
  AllocationPreview,
  CheckoutStatus,
  CloseBody,
  CommunityFunding,
  ContributeBody,
  ContributeResult,
  CreatePoolBody,
  FundingPolicy,
  FundingStatus,
  FundingSubscription,
  MyFunding,
  PoolSummary,
  TransparencyReport,
} from '@/types/funding';

const c = (slug: string) => `/communities/${encodeURIComponent(slug)}/funding`;

export const fundingApi = {
  status: () => http.get<FundingStatus>('/funding/status'),
  community: (slug: string) => http.get<CommunityFunding>(c(slug)),
  createPool: (slug: string, body: CreatePoolBody) => http.post<PoolSummary>(`${c(slug)}/pools`, body),
  setPolicy: (slug: string, body: { policy: AllocationPolicy; activityCap: number }) => http.put<FundingPolicy>(`${c(slug)}/policy`, body),
  report: (poolId: number) => http.get<TransparencyReport>(`/funding/pools/${poolId}/report`),
  preview: (poolId: number) => http.get<AllocationPreview>(`/funding/pools/${poolId}/allocation-preview`),
  contribute: (poolId: number, body: ContributeBody) => http.post<ContributeResult>(`/funding/pools/${poolId}/contributions`, body),
  close: (poolId: number, body?: CloseBody) => http.post<TransparencyReport>(`/funding/pools/${poolId}/close`, body ?? {}),
  mine: () => http.get<MyFunding>('/me/funding'),
  cancel: (subscriptionId: number) => http.post<FundingSubscription>(`/me/funding/subscriptions/${subscriptionId}/cancel`),
  /** Back from Stripe Checkout: the server asks Stripe whether the session was paid. Safe to poll. */
  confirmCheckout: (sessionId: string) => http.post<CheckoutStatus>('/funding/checkouts/confirm', { sessionId }),
  cancelCheckout: (pendingId: number) => http.post<CheckoutStatus>(`/funding/checkouts/${pendingId}/cancel`),
};

export const fundingKeys = {
  all: ['funding'] as const,
  status: ['funding', 'status'] as const,
  community: (slug: string) => ['funding', 'community', slug] as const,
  report: (id: number) => ['funding', 'report', id] as const,
  preview: (id: number) => ['funding', 'preview', id] as const,
  mine: ['funding', 'mine'] as const,
};

export const useFundingStatus = () => useQuery({ queryKey: fundingKeys.status, queryFn: fundingApi.status, staleTime: 5 * 60_000 });

export const useCommunityFunding = (slug: string, enabled = true) =>
  useQuery({ queryKey: fundingKeys.community(slug), queryFn: () => fundingApi.community(slug), enabled: enabled && !!slug });

export const usePoolReport = (id: number) =>
  useQuery({ queryKey: fundingKeys.report(id), queryFn: () => fundingApi.report(id), enabled: Number.isFinite(id) && id > 0, retry: false });

export const useAllocationPreview = (id: number, enabled: boolean) =>
  useQuery({ queryKey: fundingKeys.preview(id), queryFn: () => fundingApi.preview(id), enabled, retry: false });

export const useMyFunding = () => useQuery({ queryKey: fundingKeys.mine, queryFn: fundingApi.mine });

/** Every funding mutation can change pools, totals, reports and "my contributions": refresh all of it. */
export function useFundingMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: fundingKeys.all }),
  });
}

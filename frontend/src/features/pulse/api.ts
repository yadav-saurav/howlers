import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import { useRealtimeStore } from '@/services/realtime';
import type { CursorPage } from '@/types/api';
import type { CreatePulseBody, Pulse, PulseReason } from '@/types/moderation';

const c = (slug: string) => `/communities/${encodeURIComponent(slug)}/pulses`;

export const pulseApi = {
  active: (slug: string) => http.get<Pulse[]>(c(slug), { status: 'active' }),
  history: (slug: string, cursor?: string) => http.get<CursorPage<Pulse>>(c(slug), { status: 'closed', cursor }),
  mineActive: (limit = 5) => http.get<Pulse[]>('/pulses/active', { limit }),
  get: (id: number) => http.get<Pulse>(`/pulses/${id}`),
  create: (slug: string, body: CreatePulseBody) => http.post<Pulse>(c(slug), body),
  vote: (id: number, optionIds: number[], reasoning?: string) => http.post<Pulse>(`/pulses/${id}/vote`, { optionIds, reasoning }),
  close: (id: number) => http.post<Pulse>(`/pulses/${id}/close`),
  resolve: (id: number, optionId: number) => http.post<Pulse>(`/pulses/${id}/resolve`, { optionId }),
  reasons: (id: number) => http.get<PulseReason[]>(`/pulses/${id}/reasons`),
  remove: (id: number) => http.del<void>(`/pulses/${id}`),
};

export const pulseKeys = {
  all: ['pulses'] as const,
  active: (slug: string) => ['pulses', 'active', slug] as const,
  history: (slug: string) => ['pulses', 'history', slug] as const,
  mine: ['pulses', 'mine'] as const,
  reasons: (id: number) => ['pulses', 'reasons', id] as const,
};

/** Open pulses are pushed over `/topic/pulse.{id}` (see PulseCard); they poll every 15 s only while the socket is down. */
export function useActivePulses(slug: string, enabled = true) {
  const live = useRealtimeStore((s) => s.status === 'connected');
  return useQuery({ queryKey: pulseKeys.active(slug), queryFn: () => pulseApi.active(slug), enabled: enabled && !!slug, refetchInterval: live ? false : 15_000 });
}

export const useMyActivePulses = () => useQuery({ queryKey: pulseKeys.mine, queryFn: () => pulseApi.mineActive(5), refetchInterval: 60_000 });

export const usePulseHistory = (slug: string, enabled = true) =>
  useInfiniteQuery({
    queryKey: pulseKeys.history(slug),
    queryFn: ({ pageParam }) => pulseApi.history(slug, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: enabled && !!slug,
  });

export const usePulseReasons = (id: number, enabled: boolean) =>
  useQuery({ queryKey: pulseKeys.reasons(id), queryFn: () => pulseApi.reasons(id), enabled });

/** Any pulse change refreshes every pulse list (they're small). */
export function usePulseMutation<V>(fn: (v: V) => Promise<unknown>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: pulseKeys.all }) });
}

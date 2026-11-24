import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import { qk } from '@/services/queryKeys';
import type { Mutes } from '@/types/admin';

export const mutesApi = {
  list: () => http.get<Mutes>('/me/mutes'),
  user: (username: string, muted: boolean) =>
    muted ? http.put<void>(`/users/${encodeURIComponent(username)}/mute`) : http.del<void>(`/users/${encodeURIComponent(username)}/mute`),
  community: (slug: string, muted: boolean) =>
    muted ? http.put<void>(`/communities/${encodeURIComponent(slug)}/mute`) : http.del<void>(`/communities/${encodeURIComponent(slug)}/mute`),
};

export const mutesKey = ['mutes'] as const;

export const useMutes = (enabled = true) => useQuery({ queryKey: mutesKey, queryFn: mutesApi.list, enabled, staleTime: 60_000 });

/** Mute state is private: it lives only in the viewer's own mute list, never on public profile or community payloads. */
export function isUserMuted(m: Mutes | undefined, username: string): boolean {
  return !!m?.users.some((u) => u.username.toLowerCase() === username.toLowerCase());
}

export function isCommunityMuted(m: Mutes | undefined, slug: string): boolean {
  return !!m?.communities.some((c) => c.slug.toLowerCase() === slug.toLowerCase());
}

function useMuteMutation<V>(fn: (v: V) => Promise<void>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: mutesKey });
      qc.invalidateQueries({ queryKey: qk.posts });
      qc.invalidateQueries({ queryKey: ['comments'] });
      qc.invalidateQueries({ queryKey: ['replies'] });
    },
  });
}

export const useMuteUser = (username: string) => useMuteMutation((muted: boolean) => mutesApi.user(username, muted));
export const useMuteCommunity = (slug: string) => useMuteMutation((muted: boolean) => mutesApi.community(slug, muted));

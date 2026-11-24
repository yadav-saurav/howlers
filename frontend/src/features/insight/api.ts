import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import { contextPollInterval } from './engine';
import type { ContextView, DigestFocus, DigestLength, DigestView, Stance } from '@/types/knowledge';

export const insightApi = {
  context: (postId: number) => http.get<ContextView>(`/posts/${postId}/context`),
  label: (commentId: number, stance: Stance | null) =>
    stance ? http.put<void>(`/comments/${commentId}/stance`, { stance }) : http.del<void>(`/comments/${commentId}/stance`),
  digest: (postId: number, length: DigestLength, focus: DigestFocus) =>
    http.get<DigestView>(`/posts/${postId}/digest`, { length, focus }),
  refreshDigest: (postId: number, length: DigestLength, focus: DigestFocus) =>
    http.post<DigestView>(`/posts/${postId}/digest?length=${length}&focus=${focus}`),
};

export const insightKeys = {
  context: (postId: number) => ['context', postId] as const,
  digest: (postId: number, length: DigestLength, focus: DigestFocus) => ['digest', postId, length, focus] as const,
};

export function useContextView(postId: number, enabled: boolean) {
  return useQuery({
    queryKey: insightKeys.context(postId),
    queryFn: () => insightApi.context(postId),
    enabled,
    // AI stance suggestions run in the background; poll until they're in.
    refetchInterval: (query) => contextPollInterval(query.state.data),
  });
}

export function useLabelStance(postId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ commentId, stance }: { commentId: number; stance: Stance | null }) => insightApi.label(commentId, stance),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: insightKeys.context(postId) });
      qc.invalidateQueries({ queryKey: ['digest', postId] });
    },
  });
}

export function useDigest(postId: number, length: DigestLength, focus: DigestFocus, enabled: boolean) {
  return useQuery({
    queryKey: insightKeys.digest(postId, length, focus),
    queryFn: () => insightApi.digest(postId, length, focus),
    enabled,
    staleTime: 60_000,
    retry: false,
  });
}

export function useRefreshDigest(postId: number, length: DigestLength, focus: DigestFocus) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => insightApi.refreshDigest(postId, length, focus),
    onSuccess: (d) => qc.setQueryData(insightKeys.digest(postId, length, focus), d),
  });
}

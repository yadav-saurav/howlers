import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import { qk } from '@/services/queryKeys';
import type { CursorPage, PostDetail, UserSummary } from '@/types/api';
import type {
  Appeal,
  CommentModVerb,
  CommunityBan,
  ModAction,
  ModStats,
  MyModAction,
  PostModVerb,
  QueueItem,
  ReportBody,
} from '@/types/moderation';

const c = (slug: string) => `/communities/${encodeURIComponent(slug)}/mod`;

export const modApi = {
  report: (body: ReportBody) => http.post<void>('/reports', body),
  queue: (slug: string) => http.get<QueueItem[]>(`${c(slug)}/queue`),
  stats: (slug: string) => http.get<ModStats>(`${c(slug)}/stats`),
  log: (slug: string, cursor?: string) => http.get<CursorPage<ModAction>>(`${c(slug)}/log`, { cursor }),
  bans: (slug: string) => http.get<CommunityBan[]>(`${c(slug)}/bans`),
  ban: (slug: string, body: { username: string; reason: string; days: number | null }) => http.post<CommunityBan>(`${c(slug)}/bans`, body),
  unban: (slug: string, username: string) => http.del<void>(`${c(slug)}/bans/${encodeURIComponent(username)}`),
  appeals: (slug: string, status: 'PENDING' | 'ALL') => http.get<Appeal[]>(`${c(slug)}/appeals`, { status }),
  resolveAppeal: (id: number, body: { accept: boolean; response?: string }) => http.post<Appeal>(`/mod/appeals/${id}/resolve`, body),
  post: (id: number, verb: PostModVerb, reason?: string) => http.post<PostDetail>(`/mod/posts/${id}/${verb}`, { reason }),
  comment: (id: number, verb: CommentModVerb, reason?: string) => http.post<void>(`/mod/comments/${id}/${verb}`, { reason }),
  myAction: (id: number) => http.get<MyModAction>(`/me/mod-actions/${id}`),
  appeal: (id: number, message: string) => http.post<Appeal>(`/me/mod-actions/${id}/appeal`, { message }),
  blocks: () => http.get<UserSummary[]>('/me/blocks'),
  block: (username: string, blocked: boolean) =>
    blocked ? http.put<void>(`/users/${encodeURIComponent(username)}/block`) : http.del<void>(`/users/${encodeURIComponent(username)}/block`),
};

export const modKeys = {
  all: (slug: string) => ['mod', slug] as const,
  queue: (slug: string) => ['mod', slug, 'queue'] as const,
  stats: (slug: string) => ['mod', slug, 'stats'] as const,
  log: (slug: string) => ['mod', slug, 'log'] as const,
  bans: (slug: string) => ['mod', slug, 'bans'] as const,
  appeals: (slug: string, status: string) => ['mod', slug, 'appeals', status] as const,
  myAction: (id: number) => ['mod-action', id] as const,
  blocks: ['blocks'] as const,
};

export const useModQueue = (slug: string) => useQuery({ queryKey: modKeys.queue(slug), queryFn: () => modApi.queue(slug), retry: false });
export const useModStats = (slug: string, enabled = true) =>
  useQuery({ queryKey: modKeys.stats(slug), queryFn: () => modApi.stats(slug), enabled, retry: false, staleTime: 30_000 });
export const useModBans = (slug: string) => useQuery({ queryKey: modKeys.bans(slug), queryFn: () => modApi.bans(slug), retry: false });
export const useAppeals = (slug: string, status: 'PENDING' | 'ALL') =>
  useQuery({ queryKey: modKeys.appeals(slug, status), queryFn: () => modApi.appeals(slug, status), retry: false });
export const useModLog = (slug: string) =>
  useInfiniteQuery({
    queryKey: modKeys.log(slug),
    queryFn: ({ pageParam }) => modApi.log(slug, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    retry: false,
  });
export const useMyModAction = (id: number) =>
  useQuery({ queryKey: modKeys.myAction(id), queryFn: () => modApi.myAction(id), enabled: Number.isFinite(id), retry: false });
export const useBlocks = () => useQuery({ queryKey: modKeys.blocks, queryFn: modApi.blocks });

export function useReport() {
  return useMutation({ mutationFn: modApi.report });
}

/** Any moderator mutation: refresh the dashboard for that community plus feeds/posts/comments it may affect. */
export function useModMutation<V, R>(slug: string, fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: modKeys.all(slug) });
      qc.invalidateQueries({ queryKey: qk.posts });
      qc.invalidateQueries({ queryKey: ['comments'] });
      qc.invalidateQueries({ queryKey: ['replies'] });
      if (res && typeof res === 'object' && 'id' in res && 'viewer' in res) {
        qc.setQueryData(qk.post((res as unknown as PostDetail).id), res);
      } else {
        qc.invalidateQueries({ queryKey: ['post'] });
      }
    },
  });
}

export function useBlockUser(username: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (blocked: boolean) => modApi.block(username, blocked),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.profile(username) });
      qc.invalidateQueries({ queryKey: modKeys.blocks });
      qc.invalidateQueries({ queryKey: qk.posts });
      qc.invalidateQueries({ queryKey: ['comments'] });
    },
  });
}

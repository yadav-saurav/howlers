import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { modKeys } from '@/features/moderation/api';
import { http } from '@/services/apiClient';
import { qk } from '@/services/queryKeys';
import type { CursorPage } from '@/types/api';
import type {
  AdminCommunity,
  AdminOverview,
  AdminReport,
  AdminUser,
  AdminUserDetail,
  AuditEntry,
  FlagResolution,
  SpamFlag,
} from '@/types/admin';
import type { CommunityStatus } from '@/types/api';

const a = '/admin';

export const adminApi = {
  overview: () => http.get<AdminOverview>(`${a}/overview`),
  users: (q: string, status: string, cursor?: string) => http.get<CursorPage<AdminUser>>(`${a}/users`, { q: q || undefined, status: status || undefined, cursor }),
  user: (id: number) => http.get<AdminUserDetail>(`${a}/users/${id}`),
  suspend: (id: number, reason: string) => http.post<AdminUserDetail>(`${a}/users/${id}/suspend`, { reason }),
  unsuspend: (id: number, reason?: string) => http.post<AdminUserDetail>(`${a}/users/${id}/unsuspend`, { reason }),
  setRole: (id: number, role: 'ADMIN' | 'USER', reason?: string) => http.post<AdminUserDetail>(`${a}/users/${id}/role`, { role, reason }),
  communities: (q: string, status: string, cursor?: string) =>
    http.get<CursorPage<AdminCommunity>>(`${a}/communities`, { q: q || undefined, status: status || undefined, cursor }),
  setCommunityStatus: (slug: string, status: CommunityStatus, reason?: string) =>
    http.post<AdminCommunity>(`${a}/communities/${encodeURIComponent(slug)}/status`, { status, reason }),
  flags: (status: 'OPEN' | 'ALL', cursor?: string) => http.get<CursorPage<SpamFlag>>(`${a}/flags`, { status, cursor }),
  resolveFlag: (id: number, verdict: 'approve' | 'remove') => http.post<FlagResolution>(`${a}/flags/${id}/${verdict}`),
  reports: () => http.get<AdminReport[]>(`${a}/reports`),
  audit: (cursor?: string) => http.get<CursorPage<AuditEntry>>(`${a}/audit`, { cursor }),
};

export const adminKeys = {
  all: ['admin'] as const,
  overview: ['admin', 'overview'] as const,
  users: (q: string, status: string) => ['admin', 'users', q, status] as const,
  user: (id: number) => ['admin', 'user', id] as const,
  communities: (q: string, status: string) => ['admin', 'communities', q, status] as const,
  flags: (status: string) => ['admin', 'flags', status] as const,
  reports: ['admin', 'reports'] as const,
  audit: ['admin', 'audit'] as const,
};

const pages = { initialPageParam: undefined as string | undefined, getNextPageParam: (last: CursorPage<unknown>) => last.nextCursor ?? undefined };

export const useAdminOverview = () => useQuery({ queryKey: adminKeys.overview, queryFn: adminApi.overview, retry: false });
export const useAdminUsers = (q: string, status: string) =>
  useInfiniteQuery({ queryKey: adminKeys.users(q, status), queryFn: ({ pageParam }) => adminApi.users(q, status, pageParam), ...pages, retry: false });
export const useAdminUser = (id: number | null) =>
  useQuery({ queryKey: adminKeys.user(id ?? 0), queryFn: () => adminApi.user(id!), enabled: id != null, retry: false });
export const useAdminCommunities = (q: string, status: string) =>
  useInfiniteQuery({ queryKey: adminKeys.communities(q, status), queryFn: ({ pageParam }) => adminApi.communities(q, status, pageParam), ...pages, retry: false });
export const useAdminFlags = (status: 'OPEN' | 'ALL') =>
  useInfiniteQuery({ queryKey: adminKeys.flags(status), queryFn: ({ pageParam }) => adminApi.flags(status, pageParam), ...pages, retry: false });
export const useAdminReports = () => useQuery({ queryKey: adminKeys.reports, queryFn: adminApi.reports, retry: false });
export const useAdminAudit = () =>
  useInfiniteQuery({ queryKey: adminKeys.audit, queryFn: ({ pageParam }) => adminApi.audit(pageParam), ...pages, retry: false });

/** Any admin mutation refreshes the whole dashboard plus the feeds and posts it may affect. */
export function useAdminMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminKeys.all });
      qc.invalidateQueries({ queryKey: qk.posts });
      qc.invalidateQueries({ queryKey: ['communities'] });
      qc.invalidateQueries({ queryKey: ['mod'] });
      qc.invalidateQueries({ queryKey: ['post'] });
    },
  });
}

// ------------------------------------------------------------------ community-level holds (moderators)

export const heldApi = {
  list: (slug: string) => http.get<SpamFlag[]>(`/communities/${encodeURIComponent(slug)}/mod/held`),
  resolve: (id: number, verdict: 'approve' | 'remove') => http.post<FlagResolution>(`/mod/flags/${id}/${verdict}`),
};

export const heldKey = (slug: string) => [...modKeys.all(slug), 'held'] as const;
export const useHeld = (slug: string) => useQuery({ queryKey: heldKey(slug), queryFn: () => heldApi.list(slug), retry: false });

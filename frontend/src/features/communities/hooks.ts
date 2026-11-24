import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { qk } from '@/services/queryKeys';
import { useAuthStore } from '@/store/authStore';
import type { CommunityDetail, CommunityRole } from '@/types/api';
import { communitiesApi, type CommunityListParams } from './api';

export function useCommunities(params: CommunityListParams) {
  return useQuery({
    queryKey: qk.communities(params as Record<string, unknown>),
    queryFn: () => communitiesApi.list(params),
    placeholderData: keepPreviousData,
  });
}

export function useTrendingCommunities() {
  return useQuery({ queryKey: qk.trendingCommunities, queryFn: () => communitiesApi.trending(5), staleTime: 5 * 60_000 });
}

export function useMyCommunities() {
  const authed = useAuthStore((s) => s.status === 'authenticated');
  return useQuery({ queryKey: qk.myCommunities, queryFn: communitiesApi.mine, enabled: authed });
}

export function useCommunity(slug: string) {
  return useQuery({ queryKey: qk.community(slug), queryFn: () => communitiesApi.get(slug), enabled: !!slug });
}

export function useCreateCommunity() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: communitiesApi.create,
    onSuccess: (c) => {
      qc.setQueryData(qk.community(c.slug), c);
      qc.invalidateQueries({ queryKey: qk.myCommunities });
    },
  });
}

/** Mutations that return a fresh CommunityDetail write it straight into the cache. */
function useDetailMutation<V>(slug: string, fn: (v: V) => Promise<CommunityDetail>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (c) => {
      qc.setQueryData(qk.community(slug), c);
      qc.invalidateQueries({ queryKey: qk.myCommunities });
    },
  });
}

export function useUpdateCommunity(slug: string) {
  return useDetailMutation(slug, (body: Parameters<typeof communitiesApi.update>[1]) =>
    communitiesApi.update(slug, body),
  );
}
export function usePutRules(slug: string) {
  return useDetailMutation(slug, (rules: { title: string; description: string }[]) =>
    communitiesApi.putRules(slug, rules),
  );
}
export function usePutFlairs(slug: string) {
  return useDetailMutation(slug, (flairs: Parameters<typeof communitiesApi.putFlairs>[1]) =>
    communitiesApi.putFlairs(slug, flairs),
  );
}
export function useUploadCommunityImage(slug: string, kind: 'icon' | 'banner') {
  return useDetailMutation(slug, (file: File) =>
    kind === 'icon' ? communitiesApi.uploadIcon(slug, file) : communitiesApi.uploadBanner(slug, file),
  );
}

export function useMembership(slug: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (join: boolean) => (join ? communitiesApi.join(slug) : communitiesApi.leave(slug)),
    onMutate: async (join) => {
      await qc.cancelQueries({ queryKey: qk.community(slug) });
      const prev = qc.getQueryData<CommunityDetail>(qk.community(slug));
      if (prev) {
        qc.setQueryData<CommunityDetail>(qk.community(slug), {
          ...prev,
          isMember: join,
          memberCount: prev.memberCount + (join ? 1 : -1),
          viewer: { ...prev.viewer, isMember: join, role: join ? (prev.viewer.role ?? 'MEMBER') : null },
        });
      }
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(qk.community(slug), ctx.prev);
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: qk.community(slug) });
      qc.invalidateQueries({ queryKey: qk.myCommunities });
      qc.invalidateQueries({ queryKey: ['communities', 'list'] });
    },
  });
}

export function useCommunityMembers(slug: string, page: number, enabled = true) {
  return useQuery({
    queryKey: qk.communityMembers(slug, page),
    queryFn: () => communitiesApi.members(slug, page),
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function useSetMemberRole(slug: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ username, role }: { username: string; role: CommunityRole }) =>
      communitiesApi.setRole(slug, username, role),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['communities', 'members', slug] });
      qc.invalidateQueries({ queryKey: qk.community(slug) });
    },
  });
}

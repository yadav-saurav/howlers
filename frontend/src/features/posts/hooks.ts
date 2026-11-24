import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { mk, qk } from '@/services/queryKeys';
import type { CommunitySort, FeedTab, PostDetail, TimeRange, VoteValue } from '@/types/api';
import { postsApi } from './api';
import { patchPostEverywhere, restoreSnapshot, snapshotPosts } from './cache';
import { applyVote } from './vote';

const cursorOpts = {
  initialPageParam: undefined as string | undefined,
  getNextPageParam: (last: { nextCursor: string | null }) => last.nextCursor ?? undefined,
};

export function useFeed(tab: FeedTab, t: TimeRange) {
  return useInfiniteQuery({
    queryKey: qk.feed(tab, t),
    queryFn: ({ pageParam }) => postsApi.feed(tab, t, pageParam),
    ...cursorOpts,
  });
}

export function useCommunityPosts(slug: string, sort: CommunitySort, t: TimeRange, enabled = true) {
  return useInfiniteQuery({
    queryKey: qk.communityPosts(slug, sort, t),
    queryFn: ({ pageParam }) => postsApi.community(slug, sort, t, pageParam),
    enabled,
    ...cursorOpts,
  });
}

export function useSavedPosts() {
  return useInfiniteQuery({
    queryKey: qk.saved,
    queryFn: ({ pageParam }) => postsApi.saved(pageParam),
    ...cursorOpts,
  });
}

export function useTrendingPosts() {
  return useQuery({ queryKey: qk.trendingPosts, queryFn: () => postsApi.trending(5), staleTime: 2 * 60_000 });
}

export function usePost(id: number) {
  return useQuery({ queryKey: qk.post(id), queryFn: () => postsApi.get(id), enabled: Number.isFinite(id) });
}

export function usePostVote(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: mk.postVote(id),
    mutationFn: (value: VoteValue) => postsApi.vote(id, value),
    onMutate: async (value) => {
      await qc.cancelQueries({ queryKey: qk.post(id) });
      const snap = snapshotPosts(qc);
      patchPostEverywhere(qc, id, (p) => applyVote(p, value));
      return { snap };
    },
    onError: (_e, _v, ctx) => ctx && restoreSnapshot(qc, ctx.snap),
    onSuccess: (r) =>
      patchPostEverywhere(qc, id, (p) => ({
        ...p,
        score: r.score,
        myVote: r.myVote,
        upvotes: r.upvotes ?? p.upvotes,
        downvotes: r.downvotes ?? p.downvotes,
      })),
  });
}

export function useSavePost(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (saved: boolean) => postsApi.save(id, saved),
    onMutate: (saved) => {
      const snap = snapshotPosts(qc);
      patchPostEverywhere(qc, id, (p) => ({ ...p, saved }));
      return { snap };
    },
    onError: (_e, _v, ctx) => ctx && restoreSnapshot(qc, ctx.snap),
    onSettled: () => qc.invalidateQueries({ queryKey: qk.saved }),
  });
}

export function useHidePost(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => postsApi.hide(id),
    onSuccess: () => patchPostEverywhere(qc, id, () => null),
  });
}

export function useUnhidePost(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => postsApi.unhide(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.posts }),
  });
}

export function usePollVote(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (optionIds: number[]) => postsApi.pollVote(id, optionIds),
    onSuccess: (poll) => patchPostEverywhere(qc, id, (p) => ({ ...p, poll })),
  });
}

export function useCreatePost() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: postsApi.create,
    onSuccess: (p) => {
      qc.setQueryData(qk.post(p.id), p);
      qc.invalidateQueries({ queryKey: qk.posts });
    },
  });
}

export function useUpdatePost(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Parameters<typeof postsApi.update>[1]) => postsApi.update(id, body),
    onSuccess: (p: PostDetail) => {
      qc.setQueryData(qk.post(id), p);
      patchPostEverywhere(qc, id, (old) => ({ ...old, ...p }));
    },
  });
}

export function useDeletePost(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => postsApi.remove(id),
    onSuccess: () => {
      patchPostEverywhere(qc, id, () => null);
      qc.removeQueries({ queryKey: qk.post(id) });
    },
  });
}

export function useUploadMedia() {
  return useMutation({ mutationFn: postsApi.uploadMedia });
}

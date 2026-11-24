import { toast } from '@/components/ui/Toast';
import { useInfiniteQuery, useMutation, useQueryClient, type InfiniteData, type QueryClient } from '@tanstack/react-query';
import { mk, qk } from '@/services/queryKeys';
import type { CommentNode, CommentSort, CursorPage, PostDetail, VoteValue } from '@/types/api';
import { applyVote } from '@/features/posts/vote';
import { commentsApi } from './api';
import { appendReplies, insertReply, mapTree, markDeleted } from './tree';

type Forest = InfiniteData<CursorPage<CommentNode>, string | undefined>;

export function useComments(postId: number, sort: CommentSort) {
  return useInfiniteQuery({
    queryKey: qk.comments(postId, sort),
    queryFn: ({ pageParam }) => commentsApi.list(postId, sort, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

/** Applies a forest transform to every cached sort order of this post's comments. Pages the transform leaves untouched keep their reference. */
export function patchForests(qc: QueryClient, postId: number, fn: (nodes: CommentNode[], pageIndex: number) => CommentNode[]) {
  for (const [key, data] of qc.getQueriesData<Forest>({ queryKey: ['comments', postId] })) {
    if (!data) continue;
    let changed = false;
    const pages = data.pages.map((pg, i) => {
      const items = fn(pg.items, i);
      if (items === pg.items) return pg;
      changed = true;
      return { ...pg, items };
    });
    if (changed) qc.setQueryData<Forest>(key, { ...data, pages });
  }
}

/** Every cached page of every sort order of this post's comments, for lookups across the whole thread. */
export function cachedForests(qc: QueryClient, postId: number): CommentNode[][] {
  return qc.getQueriesData<Forest>({ queryKey: ['comments', postId] }).flatMap(([, d]) => d?.pages.map((p) => p.items) ?? []);
}

export function useCreateComment(postId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ body, parentId }: { body: string; parentId?: number | null }) =>
      commentsApi.create(postId, body, parentId),
    onSuccess: (c) => {
      patchForests(qc, postId, (nodes, i) => (c.parentId == null ? (i === 0 ? insertReply(nodes, c) : nodes) : insertReply(nodes, c)));
      // A comment held by the spam checks isn't counted until a moderator approves it (D-021).
      if (!c.pending) qc.setQueryData<PostDetail>(qk.post(postId), (p) => (p ? { ...p, commentCount: p.commentCount + 1 } : p));
      else toast.info('Your comment is waiting for a moderator to review it. Only you and the moderators can see it for now.');
    },
  });
}

export function useUpdateComment(postId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: string }) => commentsApi.update(id, body),
    onSuccess: (c) => patchForests(qc, postId, (nodes) => mapTree(nodes, c.id, (n) => ({ ...n, body: c.body, editedAt: c.editedAt }))),
  });
}

export function useDeleteComment(postId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => commentsApi.remove(id),
    onSuccess: (_r, id) => patchForests(qc, postId, (nodes) => markDeleted(nodes, id)),
  });
}

export function useCommentVote(postId: number, commentId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: mk.commentVote(commentId),
    mutationFn: (value: VoteValue) => commentsApi.vote(commentId, value),
    onMutate: (value) => {
      const snap = qc.getQueriesData<Forest>({ queryKey: ['comments', postId] });
      patchForests(qc, postId, (nodes) => mapTree(nodes, commentId, (n) => applyVote(n, value)));
      return { snap };
    },
    onError: (_e, _v, ctx) => {
      for (const [k, d] of ctx?.snap ?? []) qc.setQueryData<Forest | undefined>(k, d);
    },
    onSuccess: (r) =>
      patchForests(qc, postId, (nodes) => mapTree(nodes, commentId, (n) => ({ ...n, score: r.score, myVote: r.myVote }))),
  });
}

/** Loads the next page of direct replies for a comment and merges them into the cached forest. */
export function useLoadReplies(postId: number, sort: CommentSort) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ commentId, cursor }: { commentId: number; cursor?: string }) =>
      commentsApi.replies(commentId, sort, cursor),
    onSuccess: (page, { commentId }) =>
      patchForests(qc, postId, (nodes) => appendReplies(nodes, commentId, page.items, !!page.nextCursor)),
  });
}

import { http } from '@/services/apiClient';
import type { CommentNode, CommentSort, CursorPage, VoteResult, VoteValue } from '@/types/api';

export const commentsApi = {
  list: (postId: number, sort: CommentSort, cursor?: string) =>
    http.get<CursorPage<CommentNode>>(`/posts/${postId}/comments`, { sort, cursor, limit: 20 }),
  replies: (commentId: number, sort: CommentSort, cursor?: string) =>
    http.get<CursorPage<CommentNode>>(`/comments/${commentId}/replies`, { sort, cursor, limit: 10 }),
  create: (postId: number, body: string, parentId?: number | null) =>
    http.post<CommentNode>(`/posts/${postId}/comments`, { body, parentId: parentId ?? undefined }),
  /** Single comment without preloaded replies (used for live inserts); 404 when deleted or not visible. */
  get: (id: number) => http.get<CommentNode>(`/comments/${id}`),
  update: (id: number, body: string) => http.patch<CommentNode>(`/comments/${id}`, { body }),
  remove: (id: number) => http.del<void>(`/comments/${id}`),
  vote: (id: number, value: VoteValue) => http.post<VoteResult>(`/comments/${id}/vote`, { value }),
};

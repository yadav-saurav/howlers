import { http } from '@/services/apiClient';
import type {
  CommunitySort,
  CursorPage,
  FeedTab,
  Poll,
  PostDetail,
  PostSummary,
  PostType,
  TimeRange,
  VoteResult,
  VoteValue,
} from '@/types/api';

export interface CreatePostBody {
  communitySlug: string;
  type: PostType;
  title: string;
  bodyHtml?: string;
  url?: string;
  mediaIds?: number[];
  flairId?: number | null;
  tags?: string[];
  poll?: { options: string[]; multiple: boolean; closesAt?: string | null };
}

export interface MediaUpload {
  id: number;
  url: string;
  width: number | null;
  height: number | null;
  contentType: string;
  thumbnails?: import('@/types/api').Thumbnails | null;
}

export const postsApi = {
  feed: (tab: FeedTab, t: TimeRange, cursor?: string) =>
    http.get<CursorPage<PostSummary>>('/feed', { tab, t: tab === 'top' ? t : undefined, cursor, limit: 25 }),
  community: (slug: string, sort: CommunitySort, t: TimeRange, cursor?: string) =>
    http.get<CursorPage<PostSummary>>(`/communities/${encodeURIComponent(slug)}/posts`, {
      sort,
      t: sort === 'top' ? t : undefined,
      cursor,
      limit: 25,
    }),
  trending: (limit = 5) => http.get<PostSummary[]>('/trending/posts', { limit }),
  saved: (cursor?: string) => http.get<CursorPage<PostSummary>>('/me/saved', { cursor }),
  get: (id: number) => http.get<PostDetail>(`/posts/${id}`),
  create: (body: CreatePostBody) => http.post<PostDetail>('/posts', body),
  update: (id: number, body: { title?: string; bodyHtml?: string; tags?: string[]; flairId?: number | null }) =>
    http.patch<PostDetail>(`/posts/${id}`, body),
  remove: (id: number) => http.del<void>(`/posts/${id}`),
  vote: (id: number, value: VoteValue) => http.post<VoteResult>(`/posts/${id}/vote`, { value }),
  save: (id: number, saved: boolean) => (saved ? http.put<void>(`/posts/${id}/save`) : http.del<void>(`/posts/${id}/save`)),
  hide: (id: number) => http.put<void>(`/posts/${id}/hide`),
  unhide: (id: number) => http.del<void>(`/posts/${id}/hide`),
  pollVote: (id: number, optionIds: number[]) => http.post<Poll>(`/posts/${id}/poll/vote`, { optionIds }),
  uploadMedia: (file: File) => http.upload<MediaUpload>('/media', file),
};

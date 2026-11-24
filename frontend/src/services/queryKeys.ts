import type { CommentSort, CommunitySort, FeedTab, SearchType, TimeRange } from '@/types/api';

/** Central query-key factory so invalidation stays consistent across features. */
export const qk = {
  me: ['me'] as const,
  sessions: ['sessions'] as const,
  topics: ['topics'] as const,
  onboardingSuggestions: (topicIds: number[]) => ['onboarding', 'suggestions', topicIds] as const,

  profile: (username: string) => ['users', username] as const,
  userPosts: (username: string) => ['users', username, 'posts'] as const,
  userComments: (username: string) => ['users', username, 'comments'] as const,

  communities: (params: Record<string, unknown>) => ['communities', 'list', params] as const,
  trendingCommunities: ['communities', 'trending'] as const,
  myCommunities: ['communities', 'mine'] as const,
  community: (slug: string) => ['communities', 'detail', slug] as const,
  communityMembers: (slug: string, page: number) => ['communities', 'members', slug, page] as const,

  posts: ['posts'] as const,
  feed: (tab: FeedTab, t: TimeRange) => ['posts', 'feed', tab, t] as const,
  communityPosts: (slug: string, sort: CommunitySort, t: TimeRange) => ['posts', 'community', slug, sort, t] as const,
  trendingPosts: ['posts', 'trending'] as const,
  saved: ['posts', 'saved'] as const,
  post: (id: number) => ['post', id] as const,

  comments: (postId: number, sort: CommentSort) => ['comments', postId, sort] as const,
  replies: (commentId: number, sort: CommentSort) => ['replies', commentId, sort] as const,

  search: (q: string, type: SearchType, params: Record<string, unknown>) => ['search', q, type, params] as const,
  suggest: (q: string) => ['suggest', q] as const,

  notifications: ['notifications'] as const,
  unreadCount: ['notifications', 'unread'] as const,
};

/** Mutation keys, so live updates can tell whether the viewer has a vote in flight (`qc.isMutating({ mutationKey })`). */
export const mk = {
  postVote: (postId: number) => ['vote', 'post', postId] as const,
  commentVote: (commentId: number) => ['vote', 'comment', commentId] as const,
};

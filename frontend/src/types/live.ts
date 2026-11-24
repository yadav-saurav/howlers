import type { ID } from './api';

/** Frame on `/topic/posts` (public + restricted communities) and `/topic/community.{communityId}`. */
export interface NewPostEvent {
  type: 'POST_CREATED';
  postId: ID;
  communityId: ID;
  communitySlug: string;
  authorId: ID;
  createdAt: string;
}

/** Frames on `/topic/post.{postId}`. They carry ids and counts only, never content. */
export type PostEvent =
  | { type: 'COMMENT_CREATED'; postId: ID; commentId: ID; parentId: ID | null; authorId: ID }
  /** Batched by the server, at most every ~2 s per post. */
  | { type: 'POST_STATS'; postId: ID; score: number; commentCount: number }
  /** Batched by the server, at most every ~2 s per post. Keys are comment ids. */
  | { type: 'COMMENT_SCORES'; postId: ID; scores: Record<string, number> }
  /** Reactions on the post or one of its comments changed (D-044). Ids only. */
  | { type: 'REACTIONS'; postId: ID; targetType: 'POST' | 'COMMENT'; targetId: ID };

import type { NewPostEvent } from '@/types/live';

/**
 * Which new posts count toward a feed's "N new posts" pill.
 * - `all`: every announced post (latest / popular / rising / top, and a community page, whose topic is already scoped).
 * - `communities`: only posts in these community ids (the "Best" / for-you feed, using the viewer's joined communities).
 * - `none`: the feed can't be matched from the event alone (e.g. "Following", which is people-based), so no pill.
 */
export type FeedScope =
  | { kind: 'all' }
  | { kind: 'communities'; ids: ReadonlySet<number> }
  | { kind: 'none' };

export function isNewPostEvent(x: unknown): x is NewPostEvent {
  return !!x && typeof x === 'object' && (x as NewPostEvent).type === 'POST_CREATED' && typeof (x as NewPostEvent).postId === 'number';
}

/** Whether a POST_CREATED frame belongs in this feed for this viewer. Own posts never count (you just made them). */
export function isRelevantNewPost(ev: NewPostEvent, scope: FeedScope, meId: number | null): boolean {
  if (meId != null && ev.authorId === meId) return false;
  switch (scope.kind) {
    case 'all':
      return true;
    case 'communities':
      return scope.ids.has(ev.communityId);
    case 'none':
      return false;
  }
}

/**
 * Adds a frame to the set of unseen new post ids. Returns the same set when it doesn't count
 * (not a POST_CREATED frame, not relevant, or a duplicate), so callers can skip a re-render.
 */
export function collectNewPost(seen: ReadonlySet<number>, raw: unknown, scope: FeedScope, meId: number | null): ReadonlySet<number> {
  if (!isNewPostEvent(raw) || seen.has(raw.postId) || !isRelevantNewPost(raw, scope, meId)) return seen;
  const next = new Set(seen);
  next.add(raw.postId);
  return next;
}

export function newPostsLabel(n: number): string {
  return `${n} new ${n === 1 ? 'post' : 'posts'}`;
}

/**
 * Applies a POST_STATS frame to a cached post. Only the numbers change: `myVote` (and the viewer's optimistic state)
 * is left alone. Returns the same object when nothing changed.
 */
export function applyPostStats<P extends { score: number; commentCount: number }>(p: P, stats: { score: number; commentCount: number }): P {
  if (p.score === stats.score && p.commentCount === stats.commentCount) return p;
  return { ...p, score: stats.score, commentCount: stats.commentCount };
}

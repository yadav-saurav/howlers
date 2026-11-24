import { describe, expect, it } from 'vitest';
import type { NewPostEvent } from '@/types/live';
import { applyPostStats, collectNewPost, isRelevantNewPost, newPostsLabel, type FeedScope } from './logic';

const ev = (postId: number, extra: Partial<NewPostEvent> = {}): NewPostEvent => ({
  type: 'POST_CREATED', postId, communityId: 7, communitySlug: 'books', authorId: 2, createdAt: '2026-11-23T10:00:00Z', ...extra,
});
const all: FeedScope = { kind: 'all' };

describe('new post relevance', () => {
  it('counts everything for an all-posts feed but never my own posts', () => {
    expect(isRelevantNewPost(ev(1), all, 1)).toBe(true);
    expect(isRelevantNewPost(ev(1, { authorId: 1 }), all, 1)).toBe(false);
    expect(isRelevantNewPost(ev(1), all, null)).toBe(true);
  });

  it('filters a joined-communities feed by community id', () => {
    const scope: FeedScope = { kind: 'communities', ids: new Set([7]) };
    expect(isRelevantNewPost(ev(1), scope, 1)).toBe(true);
    expect(isRelevantNewPost(ev(1, { communityId: 8 }), scope, 1)).toBe(false);
  });

  it('counts nothing for a scope it cannot match', () => {
    expect(isRelevantNewPost(ev(1), { kind: 'none' }, 1)).toBe(false);
  });
});

describe('collectNewPost', () => {
  it('dedupes by post id and keeps the same set when nothing counts', () => {
    const s0 = new Set<number>();
    const s1 = collectNewPost(s0, ev(1), all, 9);
    expect([...s1]).toEqual([1]);
    expect(collectNewPost(s1, ev(1), all, 9)).toBe(s1);
    expect(collectNewPost(s1, ev(2, { authorId: 9 }), all, 9)).toBe(s1);
    expect(collectNewPost(s1, { type: 'COMMENT_CREATED', postId: 3 }, all, 9)).toBe(s1);
    expect(collectNewPost(s1, null, all, 9)).toBe(s1);
    expect(collectNewPost(s1, ev(2), all, 9).size).toBe(2);
  });

  it('labels the pill', () => {
    expect(newPostsLabel(1)).toBe('1 new post');
    expect(newPostsLabel(3)).toBe('3 new posts');
  });
});

describe('applyPostStats', () => {
  it('updates numbers but keeps myVote', () => {
    const p = { score: 5, commentCount: 2, myVote: 1 as const };
    const next = applyPostStats(p, { score: 9, commentCount: 3 });
    expect(next).toEqual({ score: 9, commentCount: 3, myVote: 1 });
    expect(applyPostStats(p, { score: 5, commentCount: 2 })).toBe(p);
  });
});

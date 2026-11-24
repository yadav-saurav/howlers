import { describe, expect, it } from 'vitest';
import type { CommentNode } from '@/types/api';
import { appendReplies, countLoaded, findNode, forestHas, insertLiveComment, insertReply, mapTree, markDeleted, patchCommentScores } from './tree';

let seq = 0;
function node(id: number, parentId: number | null, replies: CommentNode[] = [], extra: Partial<CommentNode> = {}): CommentNode {
  seq++;
  return {
    id, postId: 1, parentId, depth: 0, body: `c${id}`, author: { id: 9, username: 'u', displayName: null, avatarUrl: null },
    score: 1, myVote: 0, deleted: false, removed: false, createdAt: '2026-01-01T00:00:00Z', editedAt: null,
    replyCount: replies.length, replies, moreReplies: 0, ...extra,
  };
}

const forest = () => [node(1, null, [node(2, 1, [node(3, 2)])]), node(4, null)];

describe('comment tree helpers', () => {
  it('finds nested nodes', () => {
    expect(findNode(forest(), 3)?.body).toBe('c3');
    expect(findNode(forest(), 99)).toBeNull();
  });

  it('mapTree returns the same reference when nothing matches', () => {
    const f = forest();
    expect(mapTree(f, 99, (n) => n)).toBe(f);
  });

  it('mapTree updates immutably along the path only', () => {
    const f = forest();
    const next = mapTree(f, 3, (n) => ({ ...n, score: 5 }));
    expect(findNode(next, 3)?.score).toBe(5);
    expect(findNode(f, 3)?.score).toBe(1);
    expect(next[1]).toBe(f[1]);
  });

  it('inserts a top-level comment first', () => {
    const next = insertReply(forest(), node(10, null));
    expect(next[0]!.id).toBe(10);
  });

  it('inserts a reply under its parent and bumps replyCount', () => {
    const next = insertReply(forest(), node(11, 2));
    const parent = findNode(next, 2)!;
    expect(parent.replies[0]!.id).toBe(11);
    expect(parent.replyCount).toBe(2);
  });

  it('appends a page of replies without duplicates and updates moreReplies', () => {
    const f = [node(1, null, [node(2, 1)], { replyCount: 5, moreReplies: 4 })];
    const next = appendReplies(f, 1, [node(2, 1), node(5, 1), node(6, 1)], true);
    const p = findNode(next, 1)!;
    expect(p.replies.map((r) => r.id)).toEqual([2, 5, 6]);
    expect(p.moreReplies).toBe(2);
    const done = appendReplies(next, 1, [node(7, 1)], false);
    expect(findNode(done, 1)!.moreReplies).toBe(0);
  });

  it('marks deleted comments but keeps their replies', () => {
    const next = markDeleted(forest(), 2);
    const n = findNode(next, 2)!;
    expect(n.deleted).toBe(true);
    expect(n.author).toBeNull();
    expect(n.body).toBe('');
    expect(n.replies).toHaveLength(1);
  });

  it('counts loaded comments', () => {
    expect(countLoaded(forest())).toBe(4);
    expect(seq).toBeGreaterThan(0);
  });
});

describe('live comment helpers', () => {
  it('forestHas looks across pages', () => {
    expect(forestHas([[node(1, null)], [node(5, null, [node(6, 5)])]], 6)).toBe(true);
    expect(forestHas([[node(1, null)]], 6)).toBe(false);
  });

  it('inserts a live top-level comment only on the first page', () => {
    const f = forest();
    expect(insertLiveComment(f, node(20, null), true)[0]!.id).toBe(20);
    expect(insertLiveComment(f, node(20, null), false)).toBe(f);
  });

  it('appends a live reply after the loaded replies and bumps replyCount', () => {
    const f = [node(1, null, [node(2, 1)], { replyCount: 4, moreReplies: 3 })];
    const next = insertLiveComment(f, node(30, 1), false);
    const parent = findNode(next, 1)!;
    expect(parent.replies.map((r) => r.id)).toEqual([2, 30]);
    expect(parent.replyCount).toBe(5);
    expect(parent.moreReplies).toBe(3);
  });

  it('ignores a reply whose parent is not on this page, and duplicates', () => {
    const f = forest();
    expect(insertLiveComment(f, node(30, 77), true)).toBe(f);
    expect(insertLiveComment(f, node(3, 2), true)).toBe(f);
    expect(insertLiveComment(f, node(4, null), true)).toBe(f);
  });

  it('patches scores of loaded comments, keeping untouched branches', () => {
    const f = forest();
    const next = patchCommentScores(f, { '3': 12, '99': 4 });
    expect(findNode(next, 3)?.score).toBe(12);
    expect(next[1]).toBe(f[1]);
    expect(patchCommentScores(f, { '99': 4 })).toBe(f);
    expect(patchCommentScores(f, { '1': 1 })).toBe(f);
  });
});

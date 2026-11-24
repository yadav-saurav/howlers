import { describe, expect, it } from 'vitest';
import { applyVote, toggleTarget } from './vote';

const base = { score: 10, upvotes: 12, downvotes: 2, myVote: 0 as const };

describe('applyVote', () => {
  it('upvotes from neutral', () => {
    expect(applyVote(base, 1)).toMatchObject({ score: 11, upvotes: 13, downvotes: 2, myVote: 1 });
  });
  it('switches an upvote to a downvote (net -2)', () => {
    const up = applyVote(base, 1);
    expect(applyVote(up, -1)).toMatchObject({ score: 9, upvotes: 12, downvotes: 3, myVote: -1 });
  });
  it('clears a downvote', () => {
    const down = applyVote(base, -1);
    expect(applyVote(down, 0)).toEqual(base);
  });
  it('is a no-op when the vote does not change', () => {
    expect(applyVote(base, 0)).toBe(base);
  });
  it('works for items without up/down breakdown (comments)', () => {
    const c = { score: 3, myVote: 1 as const };
    const r = applyVote(c, -1);
    expect(r).toEqual({ score: 1, myVote: -1 });
    expect('upvotes' in r).toBe(false);
  });
});

describe('toggleTarget', () => {
  it('clicking the active arrow clears the vote', () => {
    expect(toggleTarget(1, 1)).toBe(0);
    expect(toggleTarget(-1, -1)).toBe(0);
  });
  it('clicking the other arrow switches', () => {
    expect(toggleTarget(1, -1)).toBe(-1);
    expect(toggleTarget(0, 1)).toBe(1);
  });
});

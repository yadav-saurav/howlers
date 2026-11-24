import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactionCount, TargetReactions } from '@/types/reactions';
import { createReactionBatcher } from './batcher';
import { REACTIONS, applyToggle, chipLabel, chunk, pickerKey, reactedByLabel } from './logic';

const count = (reaction: string, n: number, extra: Partial<ReactionCount> = {}): ReactionCount => {
  const k = REACTIONS.find((r) => r.key === reaction)!;
  return { reaction, emoji: k.emoji, label: k.label, count: n, mine: false, sample: [], others: n, ...extra };
};
const summary = (reactions: ReactionCount[]): TargetReactions => ({ targetType: 'POST', targetId: 1, reactions });

describe('catalog', () => {
  it('has a curated set of 8 to 12 distinct reactions', () => {
    expect(REACTIONS.length).toBeGreaterThanOrEqual(8);
    expect(REACTIONS.length).toBeLessThanOrEqual(12);
    expect(new Set(REACTIONS.map((r) => r.key)).size).toBe(REACTIONS.length);
    expect(REACTIONS.map((r) => r.emoji)).toContain('🐺');
  });
});

describe('applyToggle', () => {
  it('adds a new kind in catalog order with me as the reactor', () => {
    const s = applyToggle(summary([count('fire', 2, { sample: ['ana', 'ben'], others: 0 })]), 'like', true, 'me');
    expect(s.reactions.map((r) => r.reaction)).toEqual(['like', 'fire']);
    expect(s.reactions[0]).toMatchObject({ count: 1, mine: true, sample: ['me'], others: 0 });
  });

  it('joins and leaves an existing kind, keeping the sample honest', () => {
    const start = summary([count('fire', 2, { sample: ['ana', 'ben'], others: 0 })]);
    const on = applyToggle(start, 'fire', true, 'me');
    expect(on.reactions[0]).toMatchObject({ count: 3, mine: true, sample: ['ana', 'ben', 'me'], others: 0 });
    const off = applyToggle(on, 'fire', false, 'me');
    expect(off.reactions[0]).toMatchObject({ count: 2, mine: false, sample: ['ana', 'ben'], others: 0 });
  });

  it('counts me under "others" when the sample is full, and drops kinds that reach zero', () => {
    const full = summary([count('howl', 3, { sample: ['a', 'b', 'c'], others: 0 })]);
    expect(applyToggle(full, 'howl', true, 'me').reactions[0]).toMatchObject({ count: 4, others: 1, sample: ['a', 'b', 'c'] });
    const solo = summary([count('sad', 1, { mine: true, sample: ['me'], others: 0 })]);
    expect(applyToggle(solo, 'sad', false, 'me').reactions).toEqual([]);
  });

  it('is a no-op when nothing changes or the key is unknown', () => {
    const s = summary([count('like', 1, { mine: true, sample: ['me'], others: 0 })]);
    expect(applyToggle(s, 'like', true, 'me')).toBe(s);
    expect(applyToggle(s, 'fire', false, 'me')).toBe(s);
    expect(applyToggle(s, 'skull', true, 'me')).toBe(s);
  });
});

describe('labels', () => {
  it('names people, me first, then the rest as a count', () => {
    expect(reactedByLabel(count('fire', 5, { mine: true, sample: ['me', 'ana'], others: 3 }), 'me')).toBe('You, ana and 3 others');
    expect(reactedByLabel(count('fire', 2, { sample: ['ana'], others: 1 }), 'me')).toBe('ana and 1 other');
    expect(reactedByLabel(count('fire', 1, { sample: ['ana'], others: 0 }), null)).toBe('ana');
    expect(reactedByLabel(count('fire', 2, { sample: [], others: 2 }), null)).toBe('2 others');
  });

  it('gives chips a full accessible name', () => {
    expect(chipLabel(count('fire', 3, { mine: true, sample: ['me', 'ana'], others: 1 }), 'me'))
      .toBe('Fire: 3 reactions, including yours. You, ana and 1 other');
    expect(chipLabel(count('like', 1, { sample: ['bo'], others: 0 }), 'me')).toBe('Thumbs up: 1 reaction. bo');
  });
});

describe('pickerKey', () => {
  it('moves through a 5-column grid and wraps sideways', () => {
    expect(pickerKey('ArrowRight', 9, 10, 5)).toBe(0);
    expect(pickerKey('ArrowLeft', 0, 10, 5)).toBe(9);
    expect(pickerKey('ArrowDown', 2, 10, 5)).toBe(7);
    expect(pickerKey('ArrowDown', 7, 10, 5)).toBe(9);
    expect(pickerKey('ArrowUp', 7, 10, 5)).toBe(2);
    expect(pickerKey('Home', 6, 10, 5)).toBe(0);
    expect(pickerKey('End', 1, 10, 5)).toBe(9);
    expect(pickerKey('a', 1, 10, 5)).toBeNull();
  });
});

describe('batcher', () => {
  afterEach(() => vi.useRealTimers());

  it('coalesces loads made together into one request per type and fills in invisible ids', async () => {
    vi.useFakeTimers();
    const fetchMany = vi.fn(async (type: 'POST' | 'COMMENT' | 'MESSAGE', ids: number[]) =>
      ids.filter((id) => id !== 3).map((id) => ({ targetType: type, targetId: id, reactions: [count('fire', id)] })));
    const b = createReactionBatcher(fetchMany, 5);
    const loads = [b.load('COMMENT', 1), b.load('COMMENT', 2), b.load('COMMENT', 3), b.load('COMMENT', 1), b.load('POST', 9)];
    await vi.advanceTimersByTimeAsync(10);
    const [one, two, three, oneAgain, post] = await Promise.all(loads);
    expect(fetchMany).toHaveBeenCalledTimes(2);
    expect(fetchMany).toHaveBeenCalledWith('COMMENT', [1, 2, 3]);
    expect(one.reactions[0].count).toBe(1);
    expect(oneAgain).toBe(one);
    expect(two.reactions[0].count).toBe(2);
    expect(three).toEqual({ targetType: 'COMMENT', targetId: 3, reactions: [] });
    expect(post.targetId).toBe(9);
  });

  it('splits big batches and rejects every waiter when a request fails', async () => {
    vi.useFakeTimers();
    const fetchMany = vi.fn(async () => {
      throw new Error('offline');
    });
    const b = createReactionBatcher(fetchMany, 1, 2);
    const loads = [1, 2, 3].map((id) => b.load('POST', id).catch((e: Error) => e.message));
    await vi.advanceTimersByTimeAsync(5);
    expect(await Promise.all(loads)).toEqual(['offline', 'offline', 'offline']);
    expect(fetchMany).toHaveBeenCalledTimes(2);
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
  });
});

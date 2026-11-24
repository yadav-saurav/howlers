import type { VoteValue } from '@/types/api';

export interface Votable {
  score: number;
  upvotes?: number;
  downvotes?: number;
  myVote: VoteValue;
}

/**
 * Pure optimistic vote reducer: removes the previous vote's contribution and applies the new one.
 * Mirrors the server's one-vote-per-user semantics (value -1 | 0 | 1).
 */
export function applyVote<T extends Votable>(item: T, next: VoteValue): T {
  const prev = item.myVote;
  if (prev === next) return item;
  let up = item.upvotes ?? 0;
  let down = item.downvotes ?? 0;
  if (prev === 1) up -= 1;
  if (prev === -1) down -= 1;
  if (next === 1) up += 1;
  if (next === -1) down += 1;
  const result: T = { ...item, myVote: next, score: item.score - prev + next };
  if (item.upvotes !== undefined) result.upvotes = up;
  if (item.downvotes !== undefined) result.downvotes = down;
  return result;
}

/** Clicking the active arrow clears the vote; clicking the other switches it. */
export function toggleTarget(current: VoteValue, clicked: 1 | -1): VoteValue {
  return current === clicked ? 0 : clicked;
}

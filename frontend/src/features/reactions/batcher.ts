import type { ReactionTargetType, TargetReactions } from '@/types/reactions';
import { chunk } from './logic';

type Fetch = (type: ReactionTargetType, ids: number[]) => Promise<TargetReactions[]>;

interface Waiter {
  resolve: (t: TargetReactions) => void;
  reject: (e: unknown) => void;
}

/**
 * Collects per-item reaction loads made in the same tick (a thread renders dozens of comments at once) into one
 * `GET /reactions?ids=...` per target type. Ids the server leaves out (not visible to you) resolve to an empty
 * summary, so a chip row simply doesn't render.
 */
export function createReactionBatcher(fetchMany: Fetch, delayMs = 8, maxBatch = 100) {
  const pending = new Map<ReactionTargetType, Map<number, Waiter[]>>();
  const timers = new Map<ReactionTargetType, ReturnType<typeof setTimeout>>();

  const flush = async (type: ReactionTargetType) => {
    timers.delete(type);
    const batch = pending.get(type);
    pending.delete(type);
    if (!batch || batch.size === 0) return;
    for (const ids of chunk([...batch.keys()], maxBatch)) {
      try {
        const found = new Map((await fetchMany(type, ids)).map((t) => [t.targetId, t]));
        for (const id of ids) {
          const t = found.get(id) ?? { targetType: type, targetId: id, reactions: [] };
          batch.get(id)?.forEach((w) => w.resolve(t));
        }
      } catch (e) {
        for (const id of ids) batch.get(id)?.forEach((w) => w.reject(e));
      }
    }
  };

  return {
    load(type: ReactionTargetType, id: number): Promise<TargetReactions> {
      return new Promise((resolve, reject) => {
        let byId = pending.get(type);
        if (!byId) {
          byId = new Map();
          pending.set(type, byId);
        }
        const list = byId.get(id) ?? [];
        list.push({ resolve, reject });
        byId.set(id, list);
        if (!timers.has(type)) timers.set(type, setTimeout(() => void flush(type), delayMs));
      });
    },
  };
}

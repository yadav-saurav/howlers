import type { ReactionCount, ReactionKind, TargetReactions } from '@/types/reactions';

/**
 * The curated set, mirrored from the server's `GET /reactions/catalog` (D-044) so the picker renders without a round
 * trip. The server stays the source of truth: it rejects unknown keys and sends emoji and labels with every count.
 */
export const REACTIONS: readonly ReactionKind[] = [
  { key: 'like', emoji: '👍', label: 'Thumbs up' },
  { key: 'heart', emoji: '❤️', label: 'Heart' },
  { key: 'laugh', emoji: '😂', label: 'Laughing' },
  { key: 'wow', emoji: '😮', label: 'Surprised' },
  { key: 'sad', emoji: '😢', label: 'Sad' },
  { key: 'fire', emoji: '🔥', label: 'Fire' },
  { key: 'howl', emoji: '🐺', label: 'Howl' },
  { key: 'party', emoji: '🎉', label: 'Party' },
  { key: 'clap', emoji: '👏', label: 'Applause' },
  { key: 'think', emoji: '🤔', label: 'Thinking' },
];

const order = (key: string) => {
  const i = REACTIONS.findIndex((r) => r.key === key);
  return i < 0 ? REACTIONS.length : i;
};

/**
 * Optimistically toggles my reaction in a summary: adds me (count + 1, mine, my name at the end of the sample when it
 * has room) or removes me. Kinds that drop to zero disappear; new kinds slot in catalog order.
 */
export function applyToggle(s: TargetReactions, key: string, on: boolean, myName: string | null): TargetReactions {
  const existing = s.reactions.find((r) => r.reaction === key);
  if (existing && existing.mine === on) return s;
  if (!existing && !on) return s;
  let reactions: ReactionCount[];
  if (!existing) {
    const kind = REACTIONS.find((r) => r.key === key);
    if (!kind) return s;
    const added: ReactionCount = {
      reaction: key, emoji: kind.emoji, label: kind.label, count: 1, mine: true, sample: myName ? [myName] : [], others: myName ? 0 : 1,
    };
    reactions = [...s.reactions, added].sort((a, b) => order(a.reaction) - order(b.reaction));
  } else {
    reactions = s.reactions
      .map((r) => {
        if (r.reaction !== key) return r;
        if (on) {
          const room = myName != null && r.sample.length < 3 && !r.sample.includes(myName);
          return { ...r, count: r.count + 1, mine: true, sample: room ? [...r.sample, myName] : r.sample, others: room ? r.others : r.others + 1 };
        }
        const hadName = myName != null && r.sample.includes(myName);
        return {
          ...r,
          count: r.count - 1,
          mine: false,
          sample: hadName ? r.sample.filter((n) => n !== myName) : r.sample,
          others: hadName ? r.others : Math.max(0, r.others - 1),
        };
      })
      .filter((r) => r.count > 0);
  }
  return { ...s, reactions };
}

/** "mira_k, dev_o and 3 others", with "You" first when I reacted. */
export function reactedByLabel(r: ReactionCount, myName: string | null): string {
  const names = r.sample.filter((n) => n !== myName);
  const people = [...(r.mine ? ['You'] : []), ...names];
  const shownOthers = r.count - people.length;
  if (shownOthers > 0) people.push(shownOthers === 1 ? '1 other' : `${shownOthers} others`);
  if (people.length === 0) return '';
  if (people.length === 1) return people[0];
  return `${people.slice(0, -1).join(', ')} and ${people[people.length - 1]}`;
}

/** Accessible name for a reaction chip: "Fire: 3 reactions, including yours. Mira_k and 2 others". */
export function chipLabel(r: ReactionCount, myName: string | null): string {
  const n = `${r.count} ${r.count === 1 ? 'reaction' : 'reactions'}`;
  const by = reactedByLabel(r, myName);
  return `${r.label}: ${n}${r.mine ? ', including yours' : ''}${by ? `. ${by}` : ''}`;
}

/**
 * Roving focus for the picker grid (arrow keys, Home, End). Returns the next index, or null when the key isn't a
 * navigation key. Up and Down move by a row of `columns`.
 */
export function pickerKey(key: string, index: number, count: number, columns: number): number | null {
  switch (key) {
    case 'ArrowRight': return (index + 1) % count;
    case 'ArrowLeft': return (index - 1 + count) % count;
    case 'ArrowDown': return Math.min(count - 1, index + columns);
    case 'ArrowUp': return Math.max(0, index - columns);
    case 'Home': return 0;
    case 'End': return count - 1;
    default: return null;
  }
}

/** Splits a list of ids into request-sized chunks (the batch endpoint takes up to 100). */
export function chunk<T>(items: readonly T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

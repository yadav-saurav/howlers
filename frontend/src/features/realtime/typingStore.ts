import { useEffect, useState } from 'react';
import { create } from 'zustand';

/** How long "typing…" stays visible after the last TYPING frame. */
export const TYPING_TTL_MS = 5_000;

/** conversationId → userId → time of the last TYPING frame. */
type Entries = Record<number, Record<number, number>>;

interface TypingState {
  entries: Entries;
  mark: (conversationId: number, userId: number, at?: number) => void;
  clear: (conversationId: number, userId: number) => void;
}

export const useTypingStore = create<TypingState>((set) => ({
  entries: {},
  mark: (c, u, at = Date.now()) => set((s) => ({ entries: { ...s.entries, [c]: { ...s.entries[c], [u]: at } } })),
  clear: (c, u) =>
    set((s) => {
      if (!s.entries[c]?.[u]) return s;
      const conv = { ...s.entries[c] };
      delete conv[u];
      return { entries: { ...s.entries, [c]: conv } };
    }),
}));

/** User ids currently typing in a conversation. Pure, so expiry is testable with a fake clock. */
export function activeTypers(entries: Entries, conversationId: number, now: number, ttl = TYPING_TTL_MS): number[] {
  const conv = entries[conversationId];
  if (!conv) return [];
  return Object.entries(conv)
    .filter(([, at]) => now - at < ttl)
    .map(([id]) => Number(id));
}

/** Re-renders when someone starts typing and again when the indicator expires. */
export function useTypingUsers(conversationId: number): number[] {
  const conv = useTypingStore((s) => s.entries[conversationId]);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    setNow(Date.now());
    if (!conv) return;
    const latest = Math.max(0, ...Object.values(conv));
    const wait = latest + TYPING_TTL_MS - Date.now();
    if (wait <= 0) return;
    const t = window.setTimeout(() => setNow(Date.now()), wait + 50);
    return () => window.clearTimeout(t);
  }, [conv]);
  return conv ? activeTypers({ [conversationId]: conv }, conversationId, now) : [];
}

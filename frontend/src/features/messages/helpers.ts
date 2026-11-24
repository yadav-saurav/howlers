import type { InfiniteData } from '@tanstack/react-query';
import type { ConversationSummary, DmMessage, MessagePage } from '@/types/messages';

/* ------------------------------------------------------------------ reducers (pure, cache-shaped) */

/** Inserts or replaces a message in an oldest→newest list, keeping id order and no duplicates. */
export function appendMessage(items: DmMessage[], msg: DmMessage): DmMessage[] {
  const idx = items.findIndex((m) => m.id === msg.id);
  if (idx >= 0) {
    const next = items.slice();
    next[idx] = msg;
    return next;
  }
  const last = items[items.length - 1];
  if (!last || last.id < msg.id) return [...items, msg];
  return [...items, msg].sort((a, b) => a.id - b.id);
}

/** Marks a message as deleted (body cleared, media dropped) the way the server renders it. */
export function applyDelete(items: DmMessage[], messageId: number): DmMessage[] {
  let changed = false;
  const next = items.map((m) => {
    if (m.id !== messageId || m.deleted) return m;
    changed = true;
    return { ...m, deleted: true, body: '', media: [] };
  });
  return changed ? next : items;
}

/**
 * Applies a READ receipt to a conversation summary. A receipt from the other person moves the "Seen" marker forward;
 * one from me (another tab) clears my unread count.
 */
export function applyRead(s: ConversationSummary, userId: number, messageId: number, meId: number | undefined): ConversationSummary {
  if (userId === meId) return s.unreadCount === 0 ? s : { ...s, unreadCount: 0 };
  if (s.otherLastReadMessageId != null && s.otherLastReadMessageId >= messageId) return s;
  return { ...s, otherLastReadMessageId: messageId };
}

/* Infinite-query helpers. pages[0] holds the newest messages; later pages are older ("load older"). */
export type MessagePages = InfiniteData<MessagePage, unknown>;

export function pagesAppend(data: MessagePages | undefined, msg: DmMessage): MessagePages | undefined {
  if (!data || data.pages.length === 0) return data;
  const holder = data.pages.findIndex((p) => p.items.some((m) => m.id === msg.id));
  const target = holder >= 0 ? holder : 0;
  return { ...data, pages: data.pages.map((p, i) => (i === target ? { ...p, items: appendMessage(p.items, msg) } : p)) };
}

export function pagesDelete(data: MessagePages | undefined, messageId: number): MessagePages | undefined {
  if (!data) return data;
  return { ...data, pages: data.pages.map((p) => ({ ...p, items: applyDelete(p.items, messageId) })) };
}

/** Flattens pages into one oldest→newest list. */
export function flattenPages(data: MessagePages | undefined): DmMessage[] {
  if (!data) return [];
  const out: DmMessage[] = [];
  for (let i = data.pages.length - 1; i >= 0; i--) out.push(...data.pages[i].items);
  return out;
}

/* ------------------------------------------------------------------ "Seen" */

/** The newest own message the other person has read, or null. "Seen" is shown under that one. */
export function seenMessageId(messages: DmMessage[], meId: number | undefined, otherLastRead: number | null): number | null {
  if (meId == null || otherLastRead == null) return null;
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.senderId === meId && !m.deleted && m.id <= otherLastRead) return m.id;
  }
  return null;
}

/* ------------------------------------------------------------------ grouping */

export const GROUP_WINDOW_MS = 5 * 60_000;

export interface MessageGroup {
  senderId: number;
  messages: DmMessage[];
  /** A pack-room system line ("mira_k added dev_o"): always alone in its group. */
  system?: boolean;
}
export interface DaySection {
  key: string;
  label: string;
  groups: MessageGroup[];
}

const dayKey = (d: Date) => `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;

export function dayLabel(iso: string, now: number = Date.now()): string {
  const d = new Date(iso);
  const today = new Date(now);
  const yesterday = new Date(now);
  yesterday.setDate(today.getDate() - 1);
  if (dayKey(d) === dayKey(today)) return 'Today';
  if (dayKey(d) === dayKey(yesterday)) return 'Yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', ...(d.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {}) });
}

/**
 * Splits an oldest→newest list into day sections, then into runs by the same sender where each message is within
 * five minutes of the previous one.
 */
export function groupMessages(messages: DmMessage[], now: number = Date.now()): DaySection[] {
  const days: DaySection[] = [];
  let prev: DmMessage | null = null;
  for (const m of messages) {
    const d = new Date(m.createdAt);
    const key = dayKey(d);
    let day = days[days.length - 1];
    if (!day || day.key !== key) {
      day = { key, label: dayLabel(m.createdAt, now), groups: [] };
      days.push(day);
      prev = null;
    }
    const group = day.groups[day.groups.length - 1];
    if (m.kind === 'SYSTEM') {
      day.groups.push({ senderId: m.senderId, messages: [m], system: true });
      prev = null;
      continue;
    }
    const sameRun =
      group && !group.system && prev && prev.senderId === m.senderId && d.getTime() - new Date(prev.createdAt).getTime() <= GROUP_WINDOW_MS;
    if (sameRun) group.messages.push(m);
    else day.groups.push({ senderId: m.senderId, messages: [m] });
    prev = m;
  }
  return days;
}

/* ------------------------------------------------------------------ typing throttle */

/** Returns a function that reports true at most once per `intervalMs`. */
export function throttleGate(intervalMs: number, clock: () => number = Date.now): () => boolean {
  let last = -Infinity;
  return () => {
    const t = clock();
    if (t - last < intervalMs) return false;
    last = t;
    return true;
  };
}

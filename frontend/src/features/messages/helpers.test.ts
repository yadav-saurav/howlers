import { describe, expect, it } from 'vitest';
import type { ConversationSummary, DmMessage } from '@/types/messages';
import { activeTypers, TYPING_TTL_MS, useTypingStore } from '@/features/realtime/typingStore';
import { brokerUrl } from '@/services/realtime';
import {
  appendMessage,
  applyDelete,
  applyRead,
  flattenPages,
  groupMessages,
  pagesAppend,
  seenMessageId,
  throttleGate,
  type MessagePages,
} from './helpers';

const base = new Date(2026, 10, 20, 10, 0, 0).getTime();
const at = (minutes: number) => new Date(base + minutes * 60_000).toISOString();
const msg = (id: number, senderId: number, minutes: number, extra: Partial<DmMessage> = {}): DmMessage => ({
  id,
  conversationId: 1,
  senderId,
  body: `m${id}`,
  media: [],
  createdAt: at(minutes),
  deleted: false,
  kind: 'USER',
  fromBlocked: false,
  ...extra,
});
const summary = (extra: Partial<ConversationSummary> = {}): ConversationSummary => ({
  id: 1,
  other: { id: 2, username: 'bo', displayName: null, avatarUrl: null },
  lastMessage: null,
  unreadCount: 3,
  otherLastReadMessageId: 5,
  online: false,
  canMessage: true,
  cannotMessageReason: null,
  updatedAt: at(0),
  kind: 'DIRECT',
  room: null,
  ...extra,
});

describe('groupMessages', () => {
  it('groups consecutive messages by the same sender within five minutes', () => {
    const days = groupMessages([msg(1, 1, 0), msg(2, 1, 4), msg(3, 1, 9.5), msg(4, 2, 10), msg(5, 1, 11)], base);
    expect(days).toHaveLength(1);
    expect(days[0].label).toBe('Today');
    expect(days[0].groups.map((g) => g.messages.map((m) => m.id))).toEqual([[1, 2], [3], [4], [5]]);
  });

  it('starts a new section and group on a new day even for the same sender', () => {
    const now = base + 24 * 3_600_000;
    const days = groupMessages([msg(1, 1, 0), msg(2, 1, 24 * 60 + 1)], now);
    expect(days.map((d) => d.label)).toEqual(['Yesterday', 'Today']);
    expect(days[1].groups[0].messages.map((m) => m.id)).toEqual([2]);
  });

  it('treats exactly five minutes as the same run', () => {
    const days = groupMessages([msg(1, 1, 0), msg(2, 1, 5)], base);
    expect(days[0].groups).toHaveLength(1);
  });
});

describe('message reducers', () => {
  it('appendMessage appends, dedupes by id and keeps id order', () => {
    const a = [msg(1, 1, 0), msg(3, 1, 1)];
    expect(appendMessage(a, msg(4, 2, 2)).map((m) => m.id)).toEqual([1, 3, 4]);
    expect(appendMessage(a, msg(2, 2, 2)).map((m) => m.id)).toEqual([1, 2, 3]);
    const replaced = appendMessage(a, msg(3, 1, 1, { body: 'edited' }));
    expect(replaced.map((m) => m.id)).toEqual([1, 3]);
    expect(replaced[1].body).toBe('edited');
  });

  it('pagesAppend writes into the newest page and never duplicates a message held by an older page', () => {
    const data: MessagePages = {
      pages: [{ items: [msg(5, 1, 5), msg(6, 2, 6)], hasMore: true }, { items: [msg(1, 1, 0), msg(2, 2, 1)], hasMore: false }],
      pageParams: [undefined, 5],
    };
    const withNew = pagesAppend(data, msg(7, 2, 7))!;
    expect(flattenPages(withNew).map((m) => m.id)).toEqual([1, 2, 5, 6, 7]);
    const echoed = pagesAppend(withNew, msg(2, 2, 1, { body: 'again' }))!;
    expect(flattenPages(echoed).map((m) => m.id)).toEqual([1, 2, 5, 6, 7]);
    expect(echoed.pages[1].items[1].body).toBe('again');
    expect(pagesAppend(undefined, msg(1, 1, 0))).toBeUndefined();
  });

  it('applyDelete clears the body and media and is a no-op for unknown ids', () => {
    const a = [msg(1, 1, 0, { media: [{ id: 9, url: '/x.png', width: 1, height: 1, signed: false, expiresAt: null }] }), msg(2, 2, 1)];
    const d = applyDelete(a, 1);
    expect(d[0]).toMatchObject({ deleted: true, body: '', media: [] });
    expect(d[1]).toBe(a[1]);
    expect(applyDelete(a, 99)).toBe(a);
  });

  it('applyRead moves the other person’s marker forward only, and clears my unread count for my own receipts', () => {
    const s = summary();
    expect(applyRead(s, 2, 8, 1).otherLastReadMessageId).toBe(8);
    expect(applyRead(s, 2, 3, 1)).toBe(s); // never backwards
    expect(applyRead(summary({ otherLastReadMessageId: null }), 2, 1, 1).otherLastReadMessageId).toBe(1);
    const mine = applyRead(s, 1, 10, 1);
    expect(mine.unreadCount).toBe(0);
    expect(mine.otherLastReadMessageId).toBe(5);
  });
});

describe('seenMessageId', () => {
  const list = [msg(1, 1, 0), msg(2, 2, 1), msg(3, 1, 2), msg(4, 1, 3, { deleted: true, body: '' }), msg(5, 1, 4)];
  it('returns the newest own, non-deleted message the other person has read', () => {
    expect(seenMessageId(list, 1, 4)).toBe(3);
    expect(seenMessageId(list, 1, 5)).toBe(5);
    expect(seenMessageId(list, 1, 2)).toBe(1);
  });
  it('returns null when nothing of mine was read', () => {
    expect(seenMessageId(list, 1, null)).toBeNull();
    expect(seenMessageId([msg(2, 2, 0)], 1, 9)).toBeNull();
    expect(seenMessageId(list, undefined, 5)).toBeNull();
  });
});

describe('typing', () => {
  it('expires typers after the TTL', () => {
    const entries = { 1: { 2: 1_000, 3: 4_000 } };
    expect(activeTypers(entries, 1, 1_000).sort()).toEqual([2, 3]);
    expect(activeTypers(entries, 1, 1_000 + TYPING_TTL_MS)).toEqual([3]);
    expect(activeTypers(entries, 1, 4_000 + TYPING_TTL_MS)).toEqual([]);
    expect(activeTypers(entries, 7, 1_000)).toEqual([]);
  });

  it('the store marks and clears per conversation', () => {
    const s = useTypingStore.getState();
    s.mark(10, 2, 500);
    s.mark(11, 2, 600);
    expect(useTypingStore.getState().entries[10]).toEqual({ 2: 500 });
    useTypingStore.getState().clear(10, 2);
    expect(useTypingStore.getState().entries[10]).toEqual({});
    expect(useTypingStore.getState().entries[11]).toEqual({ 2: 600 });
  });

  it('throttles outgoing TYPING to once per interval', () => {
    let t = 0;
    const gate = throttleGate(2_000, () => t);
    const fired: number[] = [];
    for (t = 0; t <= 5_000; t += 500) if (gate()) fired.push(t);
    expect(fired).toEqual([0, 2_000, 4_000]);
  });
});

describe('brokerUrl', () => {
  it('uses ws for http and wss for https on the same host', () => {
    expect(brokerUrl({ protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/ws');
    expect(brokerUrl({ protocol: 'https:', host: 'howlers.example' })).toBe('wss://howlers.example/ws');
  });
});

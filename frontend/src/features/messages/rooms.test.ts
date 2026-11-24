import { describe, expect, it } from 'vitest';
import type { DmMessage, RoomDetail, RoomMember } from '@/types/messages';
import { groupMessages } from './helpers';
import {
  applyRoomRead,
  canManage,
  canRemove,
  newestOwnMessageId,
  parseUsernames,
  seenByCount,
  seenByLabel,
  typingLabel,
  validateRoomDraft,
} from './rooms';

const member = (id: number, lastRead: number | null, role: RoomMember['role'] = 'MEMBER'): RoomMember => ({
  user: { id, username: `u${id}`, displayName: null, avatarUrl: null },
  role,
  joinedAt: '2026-11-20T10:00:00Z',
  lastReadMessageId: lastRead,
  online: false,
});
const msg = (id: number, senderId: number, extra: Partial<DmMessage> = {}): DmMessage => ({
  id, conversationId: 7, senderId, body: `m${id}`, media: [], createdAt: new Date(2026, 10, 20, 10, id).toISOString(),
  deleted: false, kind: 'USER', fromBlocked: false, ...extra,
});

describe('room receipts', () => {
  const members = [member(1, 10, 'OWNER'), member(2, 12), member(3, 9), member(4, null)];

  it('counts other members who read up to the message, never me or the sender', () => {
    expect(seenByCount(10, 1, members, 1)).toBe(1); // u2 only (u1 is me and the sender)
    expect(seenByCount(9, 1, members, 1)).toBe(2);
    expect(seenByCount(13, 1, members, 1)).toBe(0);
  });

  it('labels the aggregate', () => {
    expect(seenByLabel(0, 3)).toBeNull();
    expect(seenByLabel(2, 3)).toBe('Seen by 2');
    expect(seenByLabel(3, 3)).toBe('Seen by everyone');
  });

  it('moves a member receipt forward only', () => {
    const d: RoomDetail = { id: 7, name: 'Pack', iconEmoji: null, myRole: 'OWNER', members, maxMembers: 50, createdAt: '' };
    const next = applyRoomRead(d, 3, 11);
    expect(next.members.find((m) => m.user.id === 3)?.lastReadMessageId).toBe(11);
    expect(applyRoomRead(next, 3, 5)).toBe(next);
    expect(applyRoomRead(d, 99, 11)).toBe(d);
  });

  it('finds my newest real message', () => {
    const list = [msg(1, 1), msg(2, 1, { kind: 'SYSTEM' }), msg(3, 1, { deleted: true }), msg(4, 2)];
    expect(newestOwnMessageId(list, 1)).toBe(1);
    expect(newestOwnMessageId(list, 5)).toBeNull();
  });
});

describe('room permissions', () => {
  it('mirrors the server rules', () => {
    expect(canManage('OWNER')).toBe(true);
    expect(canManage('ADMIN')).toBe(true);
    expect(canManage('MEMBER')).toBe(false);
    expect(canRemove('OWNER', 'ADMIN', false)).toBe(true);
    expect(canRemove('ADMIN', 'ADMIN', false)).toBe(false);
    expect(canRemove('ADMIN', 'MEMBER', false)).toBe(true);
    expect(canRemove('OWNER', 'OWNER', false)).toBe(false);
    expect(canRemove('OWNER', 'MEMBER', true)).toBe(false);
    expect(canRemove('MEMBER', 'MEMBER', false)).toBe(false);
  });
});

describe('create dialog helpers', () => {
  it('parses pasted usernames', () => {
    expect(parseUsernames('u/mira_k, dev_o  @sol\nMIRA_K;')).toEqual(['mira_k', 'dev_o', 'sol']);
  });

  it('validates a draft', () => {
    expect(validateRoomDraft(' ', ['a', 'b'])).toMatch(/name/);
    expect(validateRoomDraft('Pack', ['a'])).toMatch(/at least 2/);
    expect(validateRoomDraft('Pack', Array.from({ length: 50 }, (_, i) => `p${i}`))).toMatch(/up to 50/);
    expect(validateRoomDraft('Pack', ['a', 'b'])).toBeNull();
  });

  it('describes who is typing', () => {
    expect(typingLabel([])).toBeNull();
    expect(typingLabel(['ana'])).toBe('ana is typing…');
    expect(typingLabel(['ana', 'bo'])).toBe('ana and bo are typing…');
    expect(typingLabel(['a', 'b', 'c'])).toBe('3 people are typing…');
  });
});

describe('system lines in the timeline', () => {
  it('stand alone and break a sender run', () => {
    const days = groupMessages([msg(1, 1), msg(2, 1, { kind: 'SYSTEM', body: 'u1 added u3' }), msg(3, 1)]);
    expect(days[0].groups.map((g) => [g.system ?? false, g.messages.map((m) => m.id)])).toEqual([
      [false, [1]],
      [true, [2]],
      [false, [3]],
    ]);
  });
});

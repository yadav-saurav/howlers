import type { DmMessage, RoomDetail, RoomMember, RoomRole } from '@/types/messages';

/* Pack rooms (D-043): pure helpers shared by the thread, the member panel and the create dialog. */

export const ROOM_MIN_OTHERS = 2;
export const ROOM_MAX_MEMBERS = 50;
export const ROOM_ICONS = ['🐺', '🌙', '🔥', '🎉', '🏕️', '🎮', '📚', '🎧', '⚽', '💻', '🌲', '☕'] as const;

/** Members other than me and the sender who have read up to (at least) this message. */
export function seenByCount(messageId: number, senderId: number | null, members: readonly RoomMember[], meId: number | undefined): number {
  return members.filter((m) => m.user.id !== meId && m.user.id !== senderId && m.lastReadMessageId != null && m.lastReadMessageId >= messageId).length;
}

/** "Seen by 3", or "Seen by everyone" once every other member has read it; null when nobody has. */
export function seenByLabel(seen: number, others: number): string | null {
  if (seen <= 0) return null;
  return others > 0 && seen >= others ? 'Seen by everyone' : `Seen by ${seen}`;
}

/** The newest message I sent (not deleted, not a system line), which carries the room's "Seen by" receipt. */
export function newestOwnMessageId(messages: readonly DmMessage[], meId: number | undefined): number | null {
  if (meId == null) return null;
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.senderId === meId && m.kind !== 'SYSTEM' && !m.deleted) return m.id;
  }
  return null;
}

export const canManage = (role: RoomRole | undefined) => role === 'OWNER' || role === 'ADMIN';

/** The owner removes anyone else; admins remove plain members. Mirrors RoomService.removeMember. */
export function canRemove(myRole: RoomRole | undefined, target: RoomRole, isSelf: boolean): boolean {
  if (isSelf || target === 'OWNER') return false;
  if (myRole === 'OWNER') return true;
  return myRole === 'ADMIN' && target === 'MEMBER';
}

/** Applies a READ receipt to the member list so "Seen by" moves without a refetch. */
export function applyRoomRead(d: RoomDetail, userId: number, messageId: number): RoomDetail {
  let changed = false;
  const members = d.members.map((m) => {
    if (m.user.id !== userId || (m.lastReadMessageId != null && m.lastReadMessageId >= messageId)) return m;
    changed = true;
    return { ...m, lastReadMessageId: messageId };
  });
  return changed ? { ...d, members } : d;
}

/** Splits "u/mira_k, dev_o  sol" into distinct usernames (case-insensitive), keeping the first spelling. */
export function parseUsernames(text: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of text.split(/[\s,;]+/)) {
    const u = raw.trim().replace(/^@/, '').replace(/^u\//i, '');
    if (!u || seen.has(u.toLowerCase())) continue;
    seen.add(u.toLowerCase());
    out.push(u);
  }
  return out;
}

/** Client-side check for the create dialog; the server re-validates everything. */
export function validateRoomDraft(name: string, usernames: readonly string[]): string | null {
  if (!name.trim()) return 'Give the room a name.';
  if (name.trim().length > 60) return 'Room names can be up to 60 characters.';
  if (usernames.length < ROOM_MIN_OTHERS) return `Add at least ${ROOM_MIN_OTHERS} people.`;
  if (usernames.length + 1 > ROOM_MAX_MEMBERS) return `A pack room holds up to ${ROOM_MAX_MEMBERS} members.`;
  return null;
}

/** "mira_k is typing…", "mira_k and dev_o are typing…", "3 people are typing…". */
export function typingLabel(names: readonly string[]): string | null {
  if (names.length === 0) return null;
  if (names.length === 1) return `${names[0]} is typing…`;
  if (names.length === 2) return `${names[0]} and ${names[1]} are typing…`;
  return `${names.length} people are typing…`;
}

export const ROLE_LABEL: Record<RoomRole, string> = { OWNER: 'Owner', ADMIN: 'Admin', MEMBER: 'Member' };

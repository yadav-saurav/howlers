import type { ID, MediaRef, UserSummary } from './api';

/**
 * A DM image. `signed` URLs (images sent since D-024) work only for conversation participants and expire after
 * 30 to 60 minutes; refetch the messages for fresh ones. Older attachments have a public, unsigned URL.
 */
export interface DmMedia extends MediaRef {
  signed: boolean;
  expiresAt: string | null;
}

export type DmPolicy = 'EVERYONE' | 'FOLLOWING' | 'NOBODY';

export interface DmMessage {
  id: ID;
  conversationId: ID;
  senderId: ID;
  /** Plain text, ≤4000 chars; "" when deleted. */
  body: string;
  media: DmMedia[];
  createdAt: string;
  deleted: boolean;
  /** SYSTEM: a pack-room event ("mira_k added dev_ostrander"), rendered from `body` as plain text. */
  kind: 'USER' | 'SYSTEM';
  /** Pack rooms: from someone you blocked. Sent without body or media; shown collapsed. */
  fromBlocked: boolean;
}

export type ConversationKind = 'DIRECT' | 'ROOM';
export type RoomRole = 'OWNER' | 'ADMIN' | 'MEMBER';

export interface RoomInfo {
  name: string;
  iconEmoji: string | null;
  memberCount: number;
  myRole: RoomRole;
}

export interface ConversationSummary {
  id: ID;
  /** null when the other account was deleted. */
  other: UserSummary | null;
  lastMessage: { id: ID; senderId: ID; excerpt: string; createdAt: string; deleted: boolean } | null;
  unreadCount: number;
  otherLastReadMessageId: ID | null;
  online: boolean;
  canMessage: boolean;
  cannotMessageReason: string | null;
  updatedAt: string;
  /** ROOM: `other` and `otherLastReadMessageId` are null, `online` means another member is online. */
  kind: ConversationKind;
  room: RoomInfo | null;
}

export interface RoomMember {
  user: UserSummary;
  role: RoomRole;
  joinedAt: string;
  lastReadMessageId: ID | null;
  online: boolean;
}

export interface RoomDetail {
  id: ID;
  name: string;
  iconEmoji: string | null;
  myRole: RoomRole;
  /** Owner first, then admins, then by join time. */
  members: RoomMember[];
  maxMembers: number;
  createdAt: string;
}

export interface MessagePage {
  /** Oldest → newest. */
  items: DmMessage[];
  hasMore: boolean;
}

/** Frames on `/user/queue/events`. */
export type RealtimeEvent =
  | { type: 'MESSAGE'; payload: DmMessage }
  | { type: 'MESSAGE_DELETED'; payload: { conversationId: ID; messageId: ID } }
  | { type: 'READ'; payload: { conversationId: ID; userId: ID; messageId: ID } }
  | { type: 'TYPING'; payload: { conversationId: ID; userId: ID } }
  | { type: 'PRESENCE'; payload: { userId: ID; online: boolean } }
  | { type: 'NOTIFICATION'; payload: { unreadCount: number } }
  | { type: 'UNREAD_MESSAGES'; payload: { count: number } }
  /** Pack rooms: name, icon or membership changed. */
  | { type: 'ROOM_UPDATED'; payload: { conversationId: ID } }
  /** You left, were removed, or the room was deleted. */
  | { type: 'ROOM_REMOVED'; payload: { conversationId: ID } }
  /** Reactions on a message changed (ids only; refetch the summary). */
  | { type: 'REACTIONS'; payload: { targetType: 'MESSAGE'; targetId: ID; conversationId: ID } };

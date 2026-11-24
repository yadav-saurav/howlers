import type { ID } from './api';

/** Emoji reactions (D-044). Stored by key; the server sends the emoji and label with every count. */
export type ReactionTargetType = 'POST' | 'COMMENT' | 'MESSAGE';

export interface ReactionKind {
  key: string;
  emoji: string;
  label: string;
}

export interface ReactionCount {
  reaction: string;
  emoji: string;
  label: string;
  count: number;
  mine: boolean;
  /** Up to three usernames, earliest first, never people you blocked. */
  sample: string[];
  /** Everyone else who reacted this way. */
  others: number;
}

export interface TargetReactions {
  targetType: ReactionTargetType;
  targetId: ID;
  /** In catalog order; empty when nobody reacted. */
  reactions: ReactionCount[];
}

export interface ToggleResult {
  active: boolean;
  summary: TargetReactions;
}

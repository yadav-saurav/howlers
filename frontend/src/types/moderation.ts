import type { CommunityRef, ID, UserSummary } from './api';

/** Mirrors docs/API.md → Moderation (Phase 3) and Community Pulse (Phase 4). */
export type ReportTargetType = 'POST' | 'COMMENT';

export interface ReportBody {
  targetType: ReportTargetType;
  postId: ID;
  commentId?: ID;
  reason: string;
  details?: string;
}

export interface QueueItem {
  targetType: ReportTargetType;
  postId: ID;
  commentId: ID | null;
  title: string;
  excerpt: string;
  author: UserSummary | null;
  removed: boolean;
  locked: boolean;
  pinned: boolean;
  createdAt: string;
  reportCount: number;
  reasons: { reason: string; count: number }[];
  details: string[];
  firstReportedAt: string;
  lastReportedAt: string;
}

export type ModActionType =
  | 'REMOVE_POST'
  | 'APPROVE_POST'
  | 'REMOVE_COMMENT'
  | 'APPROVE_COMMENT'
  | 'LOCK'
  | 'UNLOCK'
  | 'PIN'
  | 'UNPIN'
  | 'BAN'
  | 'UNBAN'
  | 'RELEASE_HELD'
  /* Automod (D-045): written with no moderator; AUTOMOD_RULE is a moderator editing a rule. */
  | 'AUTOMOD_HOLD'
  | 'AUTOMOD_FLAG'
  | 'AUTOMOD_RULE';

export interface ModAction {
  id: ID;
  action: ModActionType;
  moderator: UserSummary | null;
  targetUser: UserSummary | null;
  postId: ID | null;
  commentId: ID | null;
  targetTitle: string | null;
  reason: string | null;
  expiresAt: string | null;
  reversed: boolean;
  createdAt: string;
}

export interface CommunityBan {
  user: UserSummary;
  reason: string;
  expiresAt: string | null;
  bannedBy: UserSummary | null;
  createdAt: string;
}

export type AppealStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export interface Appeal {
  id: ID;
  action: ModAction;
  user: UserSummary;
  message: string;
  status: AppealStatus;
  response: string | null;
  createdAt: string;
  resolvedAt: string | null;
}

export interface ModStats {
  openReports: number;
  pendingAppeals: number;
  activeBans: number;
  actionsLast30Days: number;
  removedLast30Days: number;
  /** Open spam holds (D-021). */
  heldForReview?: number;
}

export interface MyModAction {
  action: ModAction;
  community: CommunityRef;
  appeal: Appeal | null;
  canAppeal: boolean;
}

export type PostModVerb = 'remove' | 'approve' | 'lock' | 'unlock' | 'pin' | 'unpin';
export type CommentModVerb = 'remove' | 'approve';

// ------------------------------------------------------------------ Pulse

export type PulseType = 'POLL' | 'PREDICTION' | 'OPINION' | 'DEBATE';
export type PulseStatus = 'OPEN' | 'CLOSED' | 'RESOLVED';

export interface PulseOption {
  id: ID;
  label: string;
  votes: number | null;
  correct: boolean;
}

export interface Pulse {
  id: ID;
  community: CommunityRef;
  type: PulseType;
  title: string;
  description: string | null;
  multiple: boolean;
  anonymous: boolean;
  allowChange: boolean;
  closesAt: string;
  status: PulseStatus;
  createdAt: string;
  author: UserSummary | null;
  totalVoters: number;
  myOptionIds: ID[];
  resultsVisible: boolean;
  options: PulseOption[];
  resolvedOptionId: ID | null;
  accuracy: number | null;
  viewer: { canVote: boolean; canManage: boolean };
}

export interface PulseReason {
  author: UserSummary | null;
  optionId: ID;
  text: string;
  createdAt: string;
}

export interface CreatePulseBody {
  type: PulseType;
  title: string;
  description?: string;
  options: string[];
  multiple: boolean;
  anonymous: boolean;
  allowChange: boolean;
  durationHours: number;
}

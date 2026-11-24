import type { CommunityRef, CommunityStatus, ID, UserSummary, Visibility } from './api';

/** Mirrors docs/API.md → Admin, Spam holds and Mutes (D-021). */

export type SpamRule = 'LINK_VELOCITY' | 'DUPLICATE' | 'BURST' | 'MENTIONS' | 'AUTOMOD';
export type SpamFlagStatus = 'OPEN' | 'APPROVED' | 'REMOVED' | 'CLOSED';

export interface SpamFlag {
  id: ID;
  community: CommunityRef;
  author: UserSummary | null;
  authorJoinedAt: string | null;
  targetType: 'POST' | 'COMMENT';
  postId: ID | null;
  commentId: ID | null;
  postTitle: string | null;
  rules: SpamRule[];
  outcome: 'HELD' | 'REJECTED';
  details: string;
  excerpt: string;
  status: SpamFlagStatus;
  resolvedBy: UserSummary | null;
  resolvedAt: string | null;
  createdAt: string;
}

export interface FlagResolution {
  flagId: ID;
  communityId: ID;
  status: 'APPROVED' | 'REMOVED';
}

export interface DayCount {
  day: string;
  count: number;
}

export interface AdminOverview {
  users: number;
  newUsers7d: number;
  suspendedUsers: number;
  communities: number;
  quarantinedCommunities: number;
  bannedCommunities: number;
  posts: number;
  comments: number;
  openReports: number;
  openFlags: number;
  signups: DayCount[];
}

export type AccountStatus = 'ACTIVE' | 'DEACTIVATED' | 'DELETED' | 'SUSPENDED';

export interface AdminUser {
  id: ID;
  username: string;
  displayName: string | null;
  avatarUrl: string | null;
  email: string;
  role: 'USER' | 'ADMIN';
  status: AccountStatus;
  emailVerified: boolean;
  createdAt: string;
  postKarma: number;
  commentKarma: number;
  postCount: number;
  commentCount: number;
  suspendedReason: string | null;
  suspendedAt: string | null;
}

export type AdminActionType =
  | 'SUSPEND_USER'
  | 'UNSUSPEND_USER'
  | 'PROMOTE_ADMIN'
  | 'DEMOTE_ADMIN'
  | 'QUARANTINE_COMMUNITY'
  | 'BAN_COMMUNITY'
  | 'RESTORE_COMMUNITY'
  | 'APPROVE_FLAG'
  | 'REMOVE_FLAG';

export interface AuditEntry {
  id: ID;
  action: AdminActionType;
  admin: UserSummary | null;
  targetUser: UserSummary | null;
  targetCommunity: CommunityRef | null;
  flagId: ID | null;
  reason: string | null;
  createdAt: string;
}

export interface AdminUserDetail {
  user: AdminUser;
  moderatorOf: CommunityRef[];
  history: AuditEntry[];
}

export interface AdminCommunity {
  id: ID;
  slug: string;
  name: string;
  iconUrl: string | null;
  visibility: Visibility;
  status: CommunityStatus;
  statusReason: string | null;
  memberCount: number;
  postCount: number;
  createdAt: string;
  owner: UserSummary | null;
  openReports: number;
  openFlags: number;
}

export interface AdminReport {
  community: CommunityRef;
  targetType: 'POST' | 'COMMENT';
  postId: ID;
  commentId: ID | null;
  title: string;
  excerpt: string;
  author: UserSummary | null;
  reportCount: number;
  reasons: string[];
  removed: boolean;
  lastReportedAt: string;
}

export interface Mutes {
  users: UserSummary[];
  communities: CommunityRef[];
}

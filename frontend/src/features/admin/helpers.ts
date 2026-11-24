import type { AdminActionType, AdminUser, AuditEntry, DayCount, SpamRule } from '@/types/admin';
import type { CommunityStatus } from '@/types/api';

/** Short human label for each spam heuristic, shown on held items. */
export const RULE_LABELS: Record<SpamRule, string> = {
  LINK_VELOCITY: 'Many links from a new account',
  DUPLICATE: 'Same content in several communities',
  BURST: 'Posting burst from a new account',
  MENTIONS: 'Too many @mentions',
  AUTOMOD: 'A community automod rule',
};

export function ruleLabel(rule: string): string {
  return (RULE_LABELS as Record<string, string>)[rule] ?? rule.replace(/_/g, ' ').toLowerCase();
}

export const ADMIN_ACTION_LABELS: Record<AdminActionType, string> = {
  SUSPEND_USER: 'suspended',
  UNSUSPEND_USER: 'lifted the suspension of',
  PROMOTE_ADMIN: 'made an admin:',
  DEMOTE_ADMIN: 'removed admin rights from',
  QUARANTINE_COMMUNITY: 'quarantined',
  BAN_COMMUNITY: 'banned',
  RESTORE_COMMUNITY: 'restored',
  APPROVE_FLAG: 'approved held content in',
  REMOVE_FLAG: 'removed held content in',
};

/** "u/alice suspended u/bob" style sentence for an audit row (subject excluded; the UI renders the admin link). */
export function describeAudit(a: Pick<AuditEntry, 'action' | 'targetUser' | 'targetCommunity'>): string {
  const verb = (ADMIN_ACTION_LABELS as Record<string, string>)[a.action] ?? a.action.toLowerCase().replace(/_/g, ' ');
  const target = a.targetUser ? `u/${a.targetUser.username}` : a.targetCommunity ? `c/${a.targetCommunity.slug}` : '';
  return target ? `${verb} ${target}` : verb;
}

export interface ChartPoint {
  day: string;
  label: string;
  count: number;
}

/** Server days (YYYY-MM-DD, UTC) to chart points with short labels such as "Nov 9". Never invents days. */
export function signupSeries(days: DayCount[]): ChartPoint[] {
  return days.map((d) => {
    const [y, m, dd] = d.day.split('-').map(Number);
    const date = new Date(Date.UTC(y, (m ?? 1) - 1, dd ?? 1));
    const label = Number.isNaN(date.getTime())
      ? d.day
      : date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
    return { day: d.day, label, count: d.count };
  });
}

export function sumCounts(days: DayCount[]): number {
  return days.reduce((n, d) => n + d.count, 0);
}

export type UserAction = 'suspend' | 'unsuspend' | 'promote' | 'demote';

/** Which account actions to offer, mirroring the server rules (the server still decides). */
export function userActions(target: Pick<AdminUser, 'id' | 'role' | 'status'>, meId: number | undefined): UserAction[] {
  if (meId === target.id || target.status === 'DELETED') return [];
  const out: UserAction[] = [];
  if (target.status === 'SUSPENDED') out.push('unsuspend');
  else if (target.role !== 'ADMIN') out.push('suspend');
  if (target.status === 'ACTIVE') out.push(target.role === 'ADMIN' ? 'demote' : 'promote');
  return out;
}

export const COMMUNITY_STATUS_COPY: Record<CommunityStatus, { label: string; tone: 'neutral' | 'warning' | 'danger' }> = {
  ACTIVE: { label: 'Active', tone: 'neutral' },
  QUARANTINED: { label: 'Quarantined', tone: 'warning' },
  BANNED: { label: 'Banned', tone: 'danger' },
};

/** Status transitions an admin can choose from the current one. */
export function nextCommunityStatuses(current: CommunityStatus): CommunityStatus[] {
  return (['ACTIVE', 'QUARANTINED', 'BANNED'] as CommunityStatus[]).filter((s) => s !== current);
}

/** Account age in whole days at the time of the flag, for "new account" context on held items. */
export function accountAgeDays(joinedAt: string | null, at: string): number | null {
  if (!joinedAt) return null;
  const ms = Date.parse(at) - Date.parse(joinedAt);
  return Number.isFinite(ms) ? Math.max(0, Math.floor(ms / 86_400_000)) : null;
}

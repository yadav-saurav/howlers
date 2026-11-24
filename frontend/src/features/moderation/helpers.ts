import type { ModActionType } from '@/types/moderation';

export const GENERIC_REASONS = ['Spam', 'Harassment or hate', 'Misinformation', 'Off-topic', 'Other'] as const;

/** Community rule titles first (prefixed with "Breaks rule"), then generic reasons; de-duplicated, ≤100 chars each. */
export function buildReportReasons(ruleTitles: string[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  const add = (r: string) => {
    const v = r.trim().slice(0, 100);
    const key = v.toLowerCase();
    if (v && !seen.has(key)) {
      seen.add(key);
      out.push(v);
    }
  };
  ruleTitles.forEach((t) => add(`Breaks rule: ${t}`));
  GENERIC_REASONS.forEach(add);
  return out;
}

/** "permanently" or "until 3 Dec 2026 (in 5 days)". */
export function formatBanExpiry(expiresAt: string | null, now: number = Date.now()): string {
  if (!expiresAt) return 'permanently';
  const t = new Date(expiresAt).getTime();
  if (Number.isNaN(t)) return 'permanently';
  const diff = t - now;
  if (diff <= 0) return 'until an expired date';
  const date = new Date(t).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
  return `until ${date} (${timeLeft(diff)})`;
}

/** Short remaining-time label: "in 3 hours", "in 5 days". */
export function timeLeft(ms: number): string {
  const min = Math.round(ms / 60_000);
  if (min < 60) return `in ${Math.max(min, 1)} minute${min === 1 ? '' : 's'}`;
  const h = Math.round(min / 60);
  if (h < 48) return `in ${h} hour${h === 1 ? '' : 's'}`;
  const d = Math.round(h / 24);
  return `in ${d} day${d === 1 ? '' : 's'}`;
}

export const ACTION_LABELS: Record<ModActionType, string> = {
  REMOVE_POST: 'Removed post',
  APPROVE_POST: 'Approved post',
  REMOVE_COMMENT: 'Removed comment',
  APPROVE_COMMENT: 'Approved comment',
  LOCK: 'Locked post',
  UNLOCK: 'Unlocked post',
  PIN: 'Pinned post',
  UNPIN: 'Unpinned post',
  BAN: 'Banned user',
  UNBAN: 'Unbanned user',
  RELEASE_HELD: 'Released held content',
  AUTOMOD_HOLD: 'Automod held',
  AUTOMOD_FLAG: 'Automod flagged',
  AUTOMOD_RULE: 'Changed automod rules',
};

/** Automod writes its log rows with no moderator and a reason starting "Automod:" (D-045). */
export function isAutomodAction(a: { moderator: unknown; action: ModActionType; reason: string | null }): boolean {
  if (a.action === 'AUTOMOD_HOLD' || a.action === 'AUTOMOD_FLAG') return true;
  return a.moderator == null && (a.reason ?? '').startsWith('Automod:');
}

/** Headline for the affected user's page (/mod-actions/:id), in plain words. */
export function affectedUserHeadline(action: ModActionType, slug: string): string {
  switch (action) {
    case 'BAN': return `You were banned from c/${slug}`;
    case 'REMOVE_POST': return 'Your post was removed by moderators';
    case 'REMOVE_COMMENT': return 'Your comment was removed by moderators';
    default: return `${ACTION_LABELS[action]} by moderators`;
  }
}

export const BAN_DURATIONS: { value: string; label: string; days: number | null }[] = [
  { value: '1', label: '1 day', days: 1 },
  { value: '7', label: '7 days', days: 7 },
  { value: '30', label: '30 days', days: 30 },
  { value: '365', label: '1 year', days: 365 },
  { value: 'perm', label: 'Permanent', days: null },
];

import type {
  Expertise,
  FormulaWeight,
  PostExpertise,
  ReputationTier,
  ScoreComponents,
  TierInfo,
} from '@/types/recognition';

const TIER_ORDER: ReputationTier[] = ['NONE', 'CONTRIBUTOR', 'TRUSTED', 'EXPERT', 'AUTHORITY'];

export const TIER_LABELS: Record<ReputationTier, string> = {
  NONE: 'Newcomer',
  CONTRIBUTOR: 'Contributor',
  TRUSTED: 'Trusted',
  EXPERT: 'Expert',
  AUTHORITY: 'Authority',
};

export function tierRank(t: ReputationTier): number {
  return TIER_ORDER.indexOf(t);
}

/** Badge text for a topic standing: "Top contributor in Security" beats the tier name when it applies. */
export function standingLabel(s: { tier: ReputationTier; topContributor: boolean }, topicName: string): string {
  if (s.topContributor) return `Top contributor in ${topicName}`;
  if (s.tier === 'NONE') return `${topicName} newcomer`;
  return `${TIER_LABELS[s.tier]} in ${topicName}`;
}

/** Points needed for the next tier, or null at the top. Uses the server's thresholds, never hard-coded ones. */
export function nextTier(points: number, tiers: TierInfo[]): { tier: TierInfo; remaining: number; progress: number } | null {
  const sorted = [...tiers].sort((a, b) => a.minPoints - b.minPoints);
  const next = sorted.find((t) => t.minPoints > points);
  if (!next) return null;
  const prevMin = [...sorted].reverse().find((t) => t.minPoints <= points)?.minPoints ?? 0;
  const span = next.minPoints - prevMin;
  return { tier: next, remaining: Math.round((next.minPoints - points) * 10) / 10, progress: span > 0 ? (points - prevMin) / span : 0 };
}

export function formatPoints(p: number): string {
  if (Math.abs(p) >= 1000) return `${(p / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return Number.isInteger(p) ? String(p) : p.toFixed(1);
}

export function expertiseIndex(data: PostExpertise | undefined): Map<number, Expertise> {
  const m = new Map<number, Expertise>();
  for (const e of data?.authors ?? []) m.set(e.userId, e);
  return m;
}

// ------------------------------------------------------------------ moderator score

export interface ComponentRow {
  component: FormulaWeight['component'];
  label: string;
  weight: number;
  /** Weight after renormalising over the components that exist this period. */
  effectiveWeight: number;
  value: number | null;
  /** Points this component adds to the 0–100 score. */
  contribution: number;
}

/**
 * Re-derives the score from its components exactly as the server does (missing components drop out and the rest
 * are renormalised), so the page can show where every point comes from.
 */
export function explainScore(components: ScoreComponents, weights: FormulaWeight[]): { rows: ComponentRow[]; score: number } {
  const available = weights.filter((w) => components[w.component] != null);
  const total = available.reduce((s, w) => s + w.weight, 0);
  const rows = weights.map((w) => {
    const value = components[w.component];
    const effectiveWeight = value == null || total === 0 ? 0 : w.weight / total;
    return { component: w.component, label: w.label, weight: w.weight, effectiveWeight, value, contribution: value == null ? 0 : 100 * effectiveWeight * value };
  });
  const score = Math.round(rows.reduce((s, r) => s + r.contribution, 0) * 10) / 10;
  return { rows, score };
}

export function percent(v: number | null): string {
  return v == null ? '—' : `${Math.round(v * 100)}%`;
}

export function formatMinutes(m: number | null): string {
  if (m == null) return '—';
  if (m < 1) return '< 1 min';
  if (m < 60) return `${Math.round(m)} min`;
  const h = Math.floor(m / 60);
  const rest = Math.round(m % 60);
  if (h < 48) return rest ? `${h} h ${rest} min` : `${h} h`;
  return `${Math.round(h / 24)} days`;
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** "2026-11" → "November 2026". */
export function periodLabel(period: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(period);
  if (!m) return period;
  const month = Number(m[2]);
  return month >= 1 && month <= 12 ? `${MONTHS[month - 1]} ${m[1]}` : period;
}

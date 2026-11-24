import type { Pulse, PulseType } from '@/types/moderation';
import { timeLeft } from '@/features/moderation/helpers';

export interface ResultRow {
  id: number;
  label: string;
  votes: number;
  pct: number;
  mine: boolean;
  correct: boolean;
}

/**
 * Result rows with whole-number percentages of total option votes (for multiple-choice the denominator is
 * total selections, so bars sum to 100). Returns null when results are hidden (before voting while open).
 */
export function pulseResults(p: Pick<Pulse, 'options' | 'resultsVisible' | 'myOptionIds'>): ResultRow[] | null {
  if (!p.resultsVisible || p.options.some((o) => o.votes == null)) return null;
  const total = p.options.reduce((a, o) => a + (o.votes ?? 0), 0);
  return p.options.map((o) => ({
    id: o.id,
    label: o.label,
    votes: o.votes ?? 0,
    pct: total === 0 ? 0 : Math.round(((o.votes ?? 0) / total) * 100),
    mine: p.myOptionIds.includes(o.id),
    correct: o.correct,
  }));
}

/** "Closes in 3 hours" / "Closed" / "Resolved". */
export function pulseStatusLabel(p: Pick<Pulse, 'status' | 'closesAt'>, now: number = Date.now()): string {
  if (p.status === 'RESOLVED') return 'Resolved';
  const ms = new Date(p.closesAt).getTime() - now;
  if (p.status === 'CLOSED' || ms <= 0) return 'Closed';
  return `Closes ${timeLeft(ms)}`;
}

export const TYPE_LABELS: Record<PulseType, string> = {
  POLL: 'Poll',
  PREDICTION: 'Prediction',
  OPINION: 'Opinion',
  DEBATE: 'Debate',
};

export const DURATIONS = [
  { value: 1, label: '1 hour' },
  { value: 24, label: '1 day' },
  { value: 72, label: '3 days' },
  { value: 168, label: '7 days' },
  { value: 720, label: '30 days' },
];

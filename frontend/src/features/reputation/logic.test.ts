import { describe, expect, it } from 'vitest';
import type { FormulaWeight, TierInfo } from '@/types/recognition';
import { expertiseIndex, explainScore, formatMinutes, formatPoints, nextTier, periodLabel, standingLabel, tierRank } from './logic';

const TIERS: TierInfo[] = [
  { tier: 'CONTRIBUTOR', label: 'Contributor', minPoints: 5 },
  { tier: 'TRUSTED', label: 'Trusted', minPoints: 25 },
  { tier: 'EXPERT', label: 'Expert', minPoints: 100 },
  { tier: 'AUTHORITY', label: 'Authority', minPoints: 400 },
];

const WEIGHTS: FormulaWeight[] = [
  { component: 'consistency', label: 'Consistency', weight: 0.3, definition: '' },
  { component: 'responsiveness', label: 'Response time', weight: 0.2, definition: '' },
  { component: 'appealQuality', label: 'Appeal outcomes', weight: 0.2, definition: '' },
  { component: 'health', label: 'Community health', weight: 0.15, definition: '' },
  { component: 'peer', label: 'Peer feedback', weight: 0.15, definition: '' },
];

describe('topic reputation helpers', () => {
  it('labels standings, preferring top contributor', () => {
    expect(standingLabel({ tier: 'TRUSTED', topContributor: true }, 'Security')).toBe('Top contributor in Security');
    expect(standingLabel({ tier: 'EXPERT', topContributor: false }, 'Books')).toBe('Expert in Books');
    expect(tierRank('AUTHORITY')).toBeGreaterThan(tierRank('TRUSTED'));
  });

  it('computes progress to the next tier from server thresholds', () => {
    expect(nextTier(15, TIERS)).toEqual({ tier: TIERS[1], remaining: 10, progress: 0.5 });
    expect(nextTier(2, TIERS)?.tier.tier).toBe('CONTRIBUTOR');
    expect(nextTier(500, TIERS)).toBeNull();
  });

  it('formats points and indexes expertise by user', () => {
    expect(formatPoints(3)).toBe('3');
    expect(formatPoints(3.25)).toBe('3.3');
    expect(formatPoints(1500)).toBe('1.5k');
    const idx = expertiseIndex({ topics: [], authors: [{ userId: 4, topicSlug: 'books', topicName: 'Books', points: 30, tier: 'TRUSTED', topContributor: false }] });
    expect(idx.get(4)?.topicName).toBe('Books');
    expect(expertiseIndex(undefined).size).toBe(0);
  });
});

describe('moderator score explanation', () => {
  it('matches the server formula and renormalises missing components', () => {
    const all = explainScore({ consistency: 1, responsiveness: 0.5, appealQuality: 1, health: 0.8, peer: 0 }, WEIGHTS);
    expect(all.score).toBeCloseTo(100 * (0.3 + 0.1 + 0.2 + 0.12 + 0), 5);

    const partial = explainScore({ consistency: 0.5, responsiveness: null, appealQuality: null, health: null, peer: null }, WEIGHTS);
    expect(partial.score).toBe(50);
    expect(partial.rows.find((r) => r.component === 'consistency')?.effectiveWeight).toBe(1);
    expect(partial.rows.find((r) => r.component === 'peer')?.contribution).toBe(0);

    expect(explainScore({ consistency: null, responsiveness: null, appealQuality: null, health: null, peer: null }, WEIGHTS).score).toBe(0);
  });

  it('formats durations and periods', () => {
    expect(formatMinutes(null)).toBe('—');
    expect(formatMinutes(42)).toBe('42 min');
    expect(formatMinutes(125)).toBe('2 h 5 min');
    expect(formatMinutes(60 * 72)).toBe('3 days');
    expect(periodLabel('2026-11')).toBe('November 2026');
    expect(periodLabel('bad')).toBe('bad');
  });
});

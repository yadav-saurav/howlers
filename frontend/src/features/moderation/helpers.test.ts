import { describe, expect, it } from 'vitest';
import { affectedUserHeadline, buildReportReasons, formatBanExpiry, timeLeft } from './helpers';
import { pulseResults, pulseStatusLabel } from '@/features/pulse/helpers';

describe('buildReportReasons', () => {
  it('lists community rules first, then generic reasons, without duplicates', () => {
    const r = buildReportReasons(['Be kind', 'Be kind', 'No spam links']);
    expect(r.slice(0, 2)).toEqual(['Breaks rule: Be kind', 'Breaks rule: No spam links']);
    expect(r).toContain('Spam');
    expect(r[r.length - 1]).toBe('Other');
    expect(new Set(r).size).toBe(r.length);
  });
  it('caps reasons at 100 characters', () => {
    expect(buildReportReasons(['x'.repeat(200)])[0]).toHaveLength(100);
  });
});

describe('affectedUserHeadline', () => {
  it('speaks to the affected user in plain words', () => {
    expect(affectedUserHeadline('REMOVE_POST', 'books')).toBe('Your post was removed by moderators');
    expect(affectedUserHeadline('REMOVE_COMMENT', 'books')).toBe('Your comment was removed by moderators');
    expect(affectedUserHeadline('BAN', 'books')).toBe('You were banned from c/books');
    expect(affectedUserHeadline('LOCK', 'books')).toBe('Locked post by moderators');
  });
});

describe('formatBanExpiry', () => {
  const now = Date.parse('2026-11-23T12:00:00Z');
  it('handles permanent and expired bans', () => {
    expect(formatBanExpiry(null, now)).toBe('permanently');
    expect(formatBanExpiry('2026-11-22T12:00:00Z', now)).toBe('until an expired date');
  });
  it('shows a date and relative time', () => {
    expect(formatBanExpiry('2026-11-30T12:00:00Z', now)).toMatch(/^until .+ \(in 7 days\)$/);
  });
  it('rounds time left sensibly', () => {
    expect(timeLeft(30_000)).toBe('in 1 minute');
    expect(timeLeft(3 * 3600_000)).toBe('in 3 hours');
    expect(timeLeft(5 * 86_400_000)).toBe('in 5 days');
  });
});

describe('pulseResults', () => {
  const base = {
    myOptionIds: [2],
    options: [
      { id: 1, label: 'Yes', votes: 3, correct: false },
      { id: 2, label: 'No', votes: 1, correct: true },
    ],
  };
  it('hides results until visible', () => {
    expect(pulseResults({ ...base, resultsVisible: false })).toBeNull();
    expect(pulseResults({ ...base, resultsVisible: true, options: base.options.map((o) => ({ ...o, votes: null })) })).toBeNull();
  });
  it('computes percentages and marks my choice and the correct option', () => {
    const r = pulseResults({ ...base, resultsVisible: true })!;
    expect(r.map((x) => x.pct)).toEqual([75, 25]);
    expect(r[1]).toMatchObject({ mine: true, correct: true });
  });
  it('handles zero votes', () => {
    const r = pulseResults({ myOptionIds: [], resultsVisible: true, options: base.options.map((o) => ({ ...o, votes: 0 })) })!;
    expect(r.every((x) => x.pct === 0)).toBe(true);
  });
  it('labels status from closesAt', () => {
    const now = Date.parse('2026-11-23T12:00:00Z');
    expect(pulseStatusLabel({ status: 'OPEN', closesAt: '2026-11-23T15:00:00Z' }, now)).toBe('Closes in 3 hours');
    expect(pulseStatusLabel({ status: 'OPEN', closesAt: '2026-11-23T11:00:00Z' }, now)).toBe('Closed');
    expect(pulseStatusLabel({ status: 'RESOLVED', closesAt: '2026-11-23T11:00:00Z' }, now)).toBe('Resolved');
  });
});

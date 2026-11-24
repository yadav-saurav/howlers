import { describe, expect, it } from 'vitest';
import { contributionBounds, formatMinor, minorToInput, parseToMinor, presets, progressPercent, sumMinor } from './money';

describe('formatMinor', () => {
  it('formats minor units with integer math', () => {
    expect(formatMinor(123450, 'USD')).toBe('$1,234.50');
    expect(formatMinor(5, 'EUR')).toBe('€0.05');
    expect(formatMinor(0, 'GBP')).toBe('£0.00');
    expect(formatMinor(1500, 'JPY')).toBe('¥1,500');
    expect(formatMinor(-250, 'USD')).toBe('-$2.50');
    expect(formatMinor(100, 'CHF')).toBe('1.00 CHF');
  });
  it('never shows float artefacts', () => {
    // 0.1 + 0.2 would be 0.30000000000000004 in floats; in cents it's exact.
    expect(formatMinor(10 + 20, 'USD')).toBe('$0.30');
    expect(formatMinor(999_999_999_99, 'USD')).toBe('$999,999,999.99');
  });
  it('rejects non-integers', () => {
    expect(() => formatMinor(1.5, 'USD')).toThrow();
  });
});

describe('parseToMinor', () => {
  it('parses typed amounts without parseFloat', () => {
    expect(parseToMinor('12', 'USD')).toBe(1200);
    expect(parseToMinor('12.5', 'USD')).toBe(1250);
    expect(parseToMinor('0.29', 'USD')).toBe(29);
    expect(parseToMinor(' 1,200.00 ', 'EUR')).toBe(120000);
    expect(parseToMinor('1500', 'JPY')).toBe(1500);
  });
  it('rejects anything ambiguous', () => {
    for (const bad of ['', '-1', '1.234', '1e3', 'abc', '1.2.3', '.5']) {
      expect(parseToMinor(bad, 'USD')).toBeNull();
    }
    expect(parseToMinor('12.5', 'JPY')).toBeNull();
  });
  it('round-trips through minorToInput', () => {
    for (const v of [0, 1, 29, 1250, 100000]) expect(parseToMinor(minorToInput(v, 'USD'), 'USD')).toBe(v);
    expect(minorToInput(1500, 'JPY')).toBe('1500');
  });
});

describe('pool maths', () => {
  it('computes whole-percent progress, clamped', () => {
    expect(progressPercent(0, 1000)).toBe(0);
    expect(progressPercent(333, 1000)).toBe(33);
    expect(progressPercent(5000, 1000)).toBe(100);
    expect(progressPercent(10, 0)).toBe(0);
  });
  it('knows contribution bounds and presets per currency', () => {
    expect(contributionBounds('USD')).toEqual({ min: 100, max: 1_000_000 });
    expect(contributionBounds('JPY')).toEqual({ min: 1, max: 10_000 });
    expect(presets('USD')).toEqual([500, 1000, 2500]);
    expect(presets('JPY')).toEqual([500, 1000, 2500]);
  });
  it('sums allocations exactly', () => {
    expect(sumMinor([501, 500])).toBe(1001);
    expect(sumMinor([])).toBe(0);
  });
});

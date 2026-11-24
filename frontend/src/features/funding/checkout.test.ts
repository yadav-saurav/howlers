import { describe, expect, it } from 'vitest';
import { CONFIRM_POLL_MS, contributeSuffix, isFinalCheckoutState, labelFor, parseCheckoutReturn, shortModeLabel } from './money';

describe('mode labels', () => {
  it('labels demo and test money and leaves live money unlabelled', () => {
    expect(labelFor('demo')).toBe('DEMO, no real money');
    expect(labelFor('test')).toBe('TEST MODE: no real money');
    expect(labelFor('live')).toBeNull();
    expect(shortModeLabel('test')).toBe('Test');
    expect(shortModeLabel('live')).toBeNull();
    expect(contributeSuffix('demo')).toBe(' (demo)');
    expect(contributeSuffix('test')).toBe(' (test mode)');
    expect(contributeSuffix('live')).toBe('');
  });
});

describe('parseCheckoutReturn', () => {
  const p = (q: string) => parseCheckoutReturn(new URLSearchParams(q));
  it('reads the success and cancel return URLs', () => {
    expect(p('checkout=success&session_id=cs_test_a1B2')).toEqual({ kind: 'success', sessionId: 'cs_test_a1B2' });
    expect(p('checkout=cancelled&pending=42')).toEqual({ kind: 'cancelled', pendingId: 42 });
  });
  it('ignores anything else', () => {
    expect(p('')).toBeNull();
    expect(p('checkout=success')).toBeNull();
    expect(p('checkout=success&session_id={CHECKOUT_SESSION_ID}')).toBeNull();
    expect(p('checkout=success&session_id=cs_x%3Cscript%3E')).toBeNull();
    expect(p('checkout=cancelled&pending=abc')).toBeNull();
    expect(p('checkout=cancelled&pending=-1')).toBeNull();
  });
  it('polls for about a minute and stops on a final state', () => {
    expect(CONFIRM_POLL_MS.reduce((a, b) => a + b, 0)).toBeLessThanOrEqual(60_000);
    expect(isFinalCheckoutState('PENDING')).toBe(false);
    expect(isFinalCheckoutState('CONFIRMED')).toBe(true);
    expect(isFinalCheckoutState('EXPIRED')).toBe(true);
  });
});

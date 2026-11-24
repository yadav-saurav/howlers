import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/apiError';
import { outcomeCopy, outcomeFromError, outcomeFromResult } from './verifyOutcome';

describe('verify-email outcomes', () => {
  it('maps successful responses', () => {
    expect(outcomeFromResult({ status: 'VERIFIED' })).toBe('verified');
    expect(outcomeFromResult({ status: 'ALREADY_VERIFIED' })).toBe('already-verified');
    expect(outcomeFromResult(undefined)).toBe('verified');
  });

  it('maps error codes', () => {
    expect(outcomeFromError(new ApiError({ status: 400, code: 'LINK_EXPIRED', message: 'x' }))).toBe('expired');
    expect(outcomeFromError(new ApiError({ status: 400, code: 'LINK_INVALID', message: 'x' }))).toBe('invalid');
    expect(outcomeFromError(new ApiError({ status: 429, code: 'RATE_LIMITED', message: 'x' }))).toBe('rate-limited');
    expect(outcomeFromError(ApiError.network())).toBe('error');
    expect(outcomeFromError(new Error('boom'))).toBe('error');
  });

  it('has copy for every outcome', () => {
    for (const k of ['verified', 'already-verified', 'expired', 'invalid', 'rate-limited', 'error'] as const) {
      expect(outcomeCopy[k].title.length).toBeGreaterThan(0);
    }
  });
});

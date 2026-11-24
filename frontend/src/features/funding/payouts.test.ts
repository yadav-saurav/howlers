import { describe, expect, it } from 'vitest';
import { connectStatusInfo, isStripeOnboardingUrl, parseConnectReturn, payoutTone } from './payouts';

describe('connectStatusInfo', () => {
  it('offers the right next step for each state', () => {
    expect(connectStatusInfo('NOT_STARTED').action).toBe('Connect with Stripe');
    expect(connectStatusInfo('PENDING').action).toBe('Resume onboarding');
    expect(connectStatusInfo('REQUIREMENTS_DUE')).toMatchObject({ tone: 'warning', action: 'Resume onboarding' });
    expect(connectStatusInfo('ENABLED')).toEqual({ title: 'Payouts enabled', tone: 'accent', action: null });
  });
});

describe('payoutTone', () => {
  it('marks paid, failed and reversed payouts', () => {
    expect(payoutTone('PAID')).toBe('accent');
    expect(payoutTone('FAILED')).toBe('danger');
    expect(payoutTone('REVERSED')).toBe('warning');
    expect(payoutTone('HELD')).toBe('neutral');
  });
});

describe('parseConnectReturn', () => {
  it('reads only the two values Stripe is sent back with', () => {
    expect(parseConnectReturn(new URLSearchParams('connect=return'))).toBe('return');
    expect(parseConnectReturn(new URLSearchParams('connect=refresh'))).toBe('refresh');
    expect(parseConnectReturn(new URLSearchParams('connect=other'))).toBeNull();
    expect(parseConnectReturn(new URLSearchParams(''))).toBeNull();
  });
});

describe('isStripeOnboardingUrl', () => {
  it('accepts only https Stripe hosts', () => {
    expect(isStripeOnboardingUrl('https://connect.stripe.com/setup/e/acct_1/abc')).toBe(true);
    expect(isStripeOnboardingUrl('http://connect.stripe.com/setup')).toBe(false);
    expect(isStripeOnboardingUrl('https://connect.stripe.com.evil.example/setup')).toBe(false);
    expect(isStripeOnboardingUrl('https://evilstripe.com/')).toBe(false);
    expect(isStripeOnboardingUrl('not a url')).toBe(false);
  });
});

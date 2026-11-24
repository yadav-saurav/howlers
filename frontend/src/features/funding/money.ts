/**
 * Money helpers. Amounts are integer minor units (cents) end to end; nothing here ever multiplies or divides a
 * fractional number, so there is no floating-point rounding anywhere between the input box and the API.
 */
import type { AllocationPolicy, CheckoutState, FundingMode, Money, PoolStatus } from '@/types/funding';

/** Fallbacks only: the server sends the label with every amount and in the funding status. */
export const DEMO_LABEL = 'DEMO, no real money';
export const TEST_LABEL = 'TEST MODE: no real money';

export function labelFor(mode: FundingMode): string | null {
  return mode === 'live' ? null : mode === 'test' ? TEST_LABEL : DEMO_LABEL;
}

/** The short badge text next to an amount: "Demo", "Test", or nothing for live money. */
export function shortModeLabel(mode: FundingMode): string | null {
  return mode === 'live' ? null : mode === 'test' ? 'Test' : 'Demo';
}

/** Suffix for contribute buttons, so nobody mistakes simulated or test money for real money. */
export function contributeSuffix(mode: FundingMode): string {
  return mode === 'live' ? '' : mode === 'test' ? ' (test mode)' : ' (demo)';
}

export type CheckoutReturn =
  | { kind: 'success'; sessionId: string }
  | { kind: 'cancelled'; pendingId: number }
  | null;

/** Reads `?checkout=success&session_id=…` or `?checkout=cancelled&pending=…` from a pool page URL. */
export function parseCheckoutReturn(params: URLSearchParams): CheckoutReturn {
  const kind = params.get('checkout');
  if (kind === 'success') {
    const sessionId = params.get('session_id') ?? '';
    // Stripe session ids are short ASCII tokens; anything else (including the unfilled template) is ignored.
    return /^cs_[A-Za-z0-9_]{1,250}$/.test(sessionId) ? { kind: 'success', sessionId } : null;
  }
  if (kind === 'cancelled') {
    const pendingId = Number(params.get('pending'));
    return Number.isSafeInteger(pendingId) && pendingId > 0 ? { kind: 'cancelled', pendingId } : null;
  }
  return null;
}

/** Poll schedule after returning from checkout: quick at first, then slower; stop after ~1 minute. */
export const CONFIRM_POLL_MS = [1000, 1500, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 10000];

export function isFinalCheckoutState(s: CheckoutState): boolean {
  return s !== 'PENDING';
}

/** Minor-unit exponent per currency (mirrors the server list). Unknown currencies fall back to 2. */
export const EXPONENTS: Record<string, number> = { USD: 2, EUR: 2, GBP: 2, JPY: 0 };
const SYMBOLS: Record<string, string> = { USD: '$', EUR: '€', GBP: '£', JPY: '¥' };

export function exponentOf(currency: string): number {
  return EXPONENTS[currency] ?? 2;
}

function pow10(e: number): number {
  let u = 1;
  for (let i = 0; i < e; i++) u *= 10;
  return u;
}

function group(n: number): string {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** "$1,234.50" from 123450 USD. Uses integer division and modulo only. */
export function formatMinor(amountMinor: number, currency: string): string {
  if (!Number.isSafeInteger(amountMinor)) throw new Error('amount must be an integer number of minor units');
  const e = exponentOf(currency);
  const unit = pow10(e);
  const neg = amountMinor < 0;
  const abs = Math.abs(amountMinor);
  const major = Math.floor(abs / unit);
  const frac = abs % unit;
  const body = e === 0 ? group(major) : `${group(major)}.${String(frac).padStart(e, '0')}`;
  const symbol = SYMBOLS[currency];
  const text = symbol ? `${symbol}${body}` : `${body} ${currency}`;
  return neg ? `-${text}` : text;
}

export function formatMoney(m: Money): string {
  return formatMinor(m.amountMinor, m.currency);
}

/**
 * Parses what someone typed ("12", "12.5", "1,200.00") into minor units, by string surgery rather than
 * parseFloat. Returns null for anything that isn't a plain positive amount with at most `exponent` decimals.
 */
export function parseToMinor(input: string, currency: string): number | null {
  const e = exponentOf(currency);
  const s = input.trim().replace(/,/g, '');
  const re = e === 0 ? /^(\d{1,12})$/ : new RegExp(`^(\\d{1,12})(?:\\.(\\d{0,${e}}))?$`);
  const m = re.exec(s);
  if (!m) return null;
  const major = Number(m[1]);
  const frac = Number((m[2] ?? '').padEnd(e, '0') || '0');
  const minor = major * pow10(e) + frac;
  return Number.isSafeInteger(minor) ? minor : null;
}

/** Minor units back into an editable string ("12.50"), without a currency symbol. */
export function minorToInput(amountMinor: number, currency: string): string {
  const e = exponentOf(currency);
  const unit = pow10(e);
  const major = Math.floor(amountMinor / unit);
  return e === 0 ? String(major) : `${major}.${String(amountMinor % unit).padStart(e, '0')}`;
}

/** Contribution bounds, matching the server: 1 to 10,000 major units. */
export function contributionBounds(currency: string): { min: number; max: number } {
  const unit = pow10(exponentOf(currency));
  return { min: unit, max: 10_000 * unit };
}

/** Whole percent of the goal reached, clamped to 0–100, computed in integers. */
export function progressPercent(raised: number, goal: number): number {
  if (goal <= 0) return 0;
  return Math.max(0, Math.min(100, Math.floor((raised * 100) / goal)));
}

export function sumMinor(values: number[]): number {
  return values.reduce((a, b) => a + b, 0);
}

/** Preset contribution amounts in minor units for a currency (5, 10, 25 major units; 500/1000/2500 yen). */
export function presets(currency: string): number[] {
  const e = exponentOf(currency);
  return e === 0 ? [500, 1000, 2500] : [5, 10, 25].map((n) => n * pow10(e));
}

export const POLICY_LABELS: Record<AllocationPolicy, string> = {
  EQUAL: 'Equal split',
  ACTIVITY: 'Weighted by activity',
  MANUAL: 'Manual allocation',
};

export const POLICY_HINTS: Record<AllocationPolicy, string> = {
  EQUAL: 'Split equally among moderators who took at least one moderation action during the period.',
  ACTIVITY:
    'Weighted by reports resolved (each reported post or comment once, removed or approved alike, never self-reported) plus 2 points per appeal handled. Each part is capped. Removals on their own earn nothing.',
  MANUAL: 'The owner enters each moderator’s amount when the pool closes, with a public note. Amounts must add up to exactly what was raised.',
};

export const STATUS_LABELS: Record<PoolStatus, string> = {
  OPEN: 'Open',
  AWAITING_ALLOCATION: 'Awaiting allocation',
  CLOSED: 'Closed',
};

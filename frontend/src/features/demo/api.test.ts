import { describe, expect, it } from 'vitest';
import { DISMISS_KEY, isDismissed, rememberDismissed } from './api';

describe('demo notice dismissal', () => {
  it('remembers a dismissal in storage', () => {
    const map = new Map<string, string>();
    const store = { getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => void map.set(k, v) };
    expect(isDismissed(store)).toBe(false);
    rememberDismissed(store);
    expect(map.get(DISMISS_KEY)).toBe('1');
    expect(isDismissed(store)).toBe(true);
  });

  it('treats unavailable storage as not dismissed and never throws', () => {
    const broken = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    expect(isDismissed(broken)).toBe(false);
    expect(() => rememberDismissed(broken)).not.toThrow();
    expect(isDismissed(undefined)).toBe(false);
  });
});

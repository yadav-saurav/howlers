import { describe, expect, it } from 'vitest';
import { describeRule, emptyRule, toRequest, validateRule } from './helpers';

describe('automod drafts', () => {
  it('requires a name, a reason and the field the kind uses', () => {
    const e = validateRule(emptyRule('KEYWORD'));
    expect(Object.keys(e).sort()).toEqual(['name', 'pattern', 'reason']);
    const ok = { ...emptyRule('KEYWORD'), name: 'Sales', reason: 'Reviewed first', pattern: 'buy now\ncheap*' };
    expect(validateRule(ok)).toEqual({});
  });

  it('rejects wildcard-only keywords, bad domains and out-of-range numbers', () => {
    const base = { name: 'n', reason: 'r' };
    expect(validateRule({ ...emptyRule('KEYWORD'), ...base, pattern: '**' }).pattern).toMatch(/wildcards/);
    expect(validateRule({ ...emptyRule('DOMAIN'), ...base, pattern: 'spam.example\nnot a domain' }).pattern).toMatch(/not a domain/);
    expect(validateRule({ ...emptyRule('DOMAIN'), ...base, pattern: 'https://www.spam.example/path' })).toEqual({});
    expect(validateRule({ ...emptyRule('LINK_LIMIT'), ...base, threshold: 101 }).threshold).toBeDefined();
    expect(validateRule({ ...emptyRule('ACCOUNT_AGE'), ...base, threshold: 0 }).threshold).toBeDefined();
    expect(validateRule({ ...emptyRule('KARMA'), ...base, threshold: 2.5 }).threshold).toBeDefined();
    expect(validateRule({ ...emptyRule('REGEX'), ...base, pattern: 'x'.repeat(501) }).pattern).toMatch(/500/);
  });

  it('describes rules in plain words', () => {
    expect(describeRule({ ...emptyRule('KEYWORD'), pattern: 'a\nb\n\nc', action: 'REMOVE' }))
      .toBe('Keywords · 3 phrases · remove · posts and comments');
    expect(describeRule({ ...emptyRule('LINK_LIMIT'), threshold: 1, appliesTo: 'COMMENTS', action: 'FLAG' }))
      .toBe('Link limit · more than 1 link · flag only · comments only');
    expect(describeRule({ ...emptyRule('ACCOUNT_AGE'), threshold: 7 })).toBe('Minimum account age · accounts under 7 days · hold for review · posts and comments');
  });

  it('sends only the field the kind uses', () => {
    expect(toRequest({ ...emptyRule('KARMA'), pattern: 'stale', threshold: 5, name: ' n ', reason: ' r ' }))
      .toMatchObject({ pattern: null, threshold: 5, name: 'n', reason: 'r' });
    expect(toRequest({ ...emptyRule('REGEX'), pattern: '\\d+', threshold: 3 })).toMatchObject({ pattern: '\\d+', threshold: null });
  });
});

import { describe, expect, it } from 'vitest';
import type { ContextView, DigestLine, DigestView } from '@/types/knowledge';
import { contextPollInterval, coverageNote, digestEngineLabel, lineSources, stanceOrigin } from './engine';

const line = (over: Partial<DigestLine> = {}): DigestLine => ({ text: 't', commentId: 7, kind: 'OPINION', author: null, ...over });

const digest = (coverage: DigestView['sections']['coverage']): DigestView =>
  ({ sections: { coverage } }) as unknown as DigestView;

describe('digestEngineLabel', () => {
  it('names AI digests and never labels other engines as AI', () => {
    expect(digestEngineLabel('llm-v1')).toEqual({ label: 'Summarised by AI', ai: true });
    expect(digestEngineLabel('extractive-v2')).toEqual({ label: 'Extractive summary', ai: false });
    expect(digestEngineLabel('something-else').ai).toBe(false);
  });
});

describe('lineSources', () => {
  it('uses every cited comment, falling back to the primary one', () => {
    expect(lineSources(line({ commentIds: [3, 4] }))).toEqual([3, 4]);
    expect(lineSources(line({ commentIds: [] }))).toEqual([7]);
    expect(lineSources(line({ commentIds: null }))).toEqual([7]);
    expect(lineSources(line())).toEqual([7]);
  });
});

describe('coverageNote', () => {
  it('prefers the server note and otherwise explains partial coverage', () => {
    expect(coverageNote(digest({ included: 5, total: 5, note: 'Covers all 5 comments.' }))).toBe('Covers all 5 comments.');
    expect(coverageNote(digest({ included: 120, total: 480, note: null }))).toBe('Covers 120 of 480 comments.');
    expect(coverageNote(digest({ included: 5, total: 5, note: null }))).toBeNull();
    expect(coverageNote(digest(null))).toBeNull();
  });
});

describe('stanceOrigin', () => {
  it('marks AI suggestions and keeps reader labels unmarked', () => {
    expect(stanceOrigin({ suggested: true, stanceSource: 'AI' }).marker).toBe('AI-suggested');
    expect(stanceOrigin({ suggested: true, stanceSource: 'RULES' })).toMatchObject({ source: 'RULES', marker: null });
    expect(stanceOrigin({ suggested: false, stanceSource: 'READERS' }).title).toBe('Labelled by readers');
  });

  it('infers the source for responses without stanceSource', () => {
    expect(stanceOrigin({ suggested: true }).source).toBe('RULES');
    expect(stanceOrigin({ suggested: false }).source).toBe('READERS');
  });
});

describe('contextPollInterval', () => {
  it('polls only while AI suggestions are pending', () => {
    const v = (state: string) => ({ aiStances: { state } }) as unknown as ContextView;
    expect(contextPollInterval(v('PENDING'))).toBe(3000);
    expect(contextPollInterval(v('READY'))).toBe(false);
    expect(contextPollInterval(v('OFF'))).toBe(false);
    expect(contextPollInterval(undefined)).toBe(false);
  });
});

import { describe, expect, it } from 'vitest';
import { FAQ, filterFaq } from './faq';

describe('filterFaq', () => {
  it('returns everything for an empty or blank query', () => {
    expect(filterFaq(FAQ, '')).toBe(FAQ);
    expect(filterFaq(FAQ, '   ')).toBe(FAQ);
  });
  it('matches questions and answers, case-insensitively, requiring every word', () => {
    expect(filterFaq(FAQ, 'ENCRYPTED').map((f) => f.id)).toContain('e2ee');
    expect(filterFaq(FAQ, 'appeal reversed').map((f) => f.id)).toEqual(['appeal']);
    expect(filterFaq(FAQ, 'appeal banana')).toEqual([]);
  });
  it('ignores apostrophes and accents', () => {
    expect(filterFaq(FAQ, 'moderators decision').map((f) => f.id)).toEqual(['appeal']);
    expect(filterFaq([{ id: 'x', q: 'Café', a: '' }], 'cafe')).toHaveLength(1);
  });
  it('has unique ids', () => {
    expect(new Set(FAQ.map((f) => f.id)).size).toBe(FAQ.length);
  });
});

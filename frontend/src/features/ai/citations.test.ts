import { describe, expect, it } from 'vitest';
import { linkCitations, parseCitationHref } from './citations';

describe('linkCitations', () => {
  it('links only citations that map to a source', () => {
    expect(linkCitations('Yes [1][2], not [9].', 7, 2)).toBe('Yes [1](#src-7-1)[2](#src-7-2), not [9].');
  });
  it('leaves existing markdown links alone', () => {
    expect(linkCitations('see [1](https://x.y)', 1, 3)).toBe('see [1](https://x.y)');
  });
  it('round-trips through parseCitationHref', () => {
    expect(parseCitationHref('#src-7-2')).toEqual({ messageId: 7, n: 2 });
    expect(parseCitationHref('https://example.com')).toBeNull();
  });
});

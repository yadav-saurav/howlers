import { describe, expect, it } from 'vitest';
import { moveItem, parseTags } from './tags';

describe('parseTags', () => {
  it('normalises, de-duplicates and caps tags', () => {
    expect(parseTags(' Java, System Design , java,, C++ ')).toEqual(['java', 'system-design', 'c']);
    expect(parseTags('a,b,c,d,e,f,g,h,i,j')).toHaveLength(8);
    expect(parseTags('')).toEqual([]);
  });
});

describe('moveItem', () => {
  it('moves an element and leaves the input untouched', () => {
    const list = [1, 2, 3, 4];
    expect(moveItem(list, 0, 2)).toEqual([2, 3, 1, 4]);
    expect(moveItem(list, 3, 0)).toEqual([4, 1, 2, 3]);
    expect(list).toEqual([1, 2, 3, 4]);
  });
  it('ignores out-of-range moves', () => {
    expect(moveItem([1, 2], 0, 5)).toEqual([1, 2]);
  });
});

import { describe, expect, it } from 'vitest';
import {
  activeIndex,
  activeSegmentIndex,
  countCues,
  formatClock,
  formatDuration,
  parseSourceRefs,
  parseTimestamp,
  progressPercent,
} from './helpers';

describe('parseTimestamp', () => {
  it('parses seconds, m:ss and h:mm:ss', () => {
    expect(parseTimestamp('45')).toBe(45);
    expect(parseTimestamp('1:05')).toBe(65);
    expect(parseTimestamp('1:02:03')).toBe(3723);
    expect(parseTimestamp('00:00:01.5')).toBe(1.5);
  });
  it('rejects malformed values', () => {
    expect(parseTimestamp('')).toBeNull();
    expect(parseTimestamp('1:75')).toBeNull();
    expect(parseTimestamp('abc')).toBeNull();
    expect(parseTimestamp('1::2')).toBeNull();
  });
});

describe('formatting', () => {
  it('formats clocks', () => {
    expect(formatClock(63)).toBe('1:03');
    expect(formatClock(3723)).toBe('1:02:03');
    expect(formatClock(NaN)).toBe('0:00');
  });
  it('formats human durations', () => {
    expect(formatDuration(2700)).toBe('45 min');
    expect(formatDuration(3723)).toBe('1 h 2 min');
    expect(formatDuration(7200)).toBe('2 h');
    expect(formatDuration(null)).toBe('—');
  });
  it('computes progress percent', () => {
    expect(progressPercent(300, 600)).toBe(50);
    expect(progressPercent(900, 600)).toBe(100);
    expect(progressPercent(undefined, 600)).toBe(0);
  });
});

describe('active lookups', () => {
  const chapters = [{ s: 0 }, { s: 60 }, { s: 300 }];
  it('finds the active chapter', () => {
    expect(activeIndex(chapters, 0, (c) => c.s)).toBe(0);
    expect(activeIndex(chapters, 59.9, (c) => c.s)).toBe(0);
    expect(activeIndex(chapters, 60, (c) => c.s)).toBe(1);
    expect(activeIndex(chapters, 9999, (c) => c.s)).toBe(2);
    expect(activeIndex([{ s: 10 }], 5, (c) => c.s)).toBe(-1);
  });
  it('finds the active transcript segment, none in gaps', () => {
    const segs = [
      { startSeconds: 0, endSeconds: 4 },
      { startSeconds: 10, endSeconds: 15 },
    ];
    expect(activeSegmentIndex(segs, 2)).toBe(0);
    expect(activeSegmentIndex(segs, 7)).toBe(-1);
    expect(activeSegmentIndex(segs, 12)).toBe(1);
  });
});

describe('parseSourceRefs', () => {
  it('parses links, ids and comment anchors and drops duplicates', () => {
    const { refs, invalid } = parseSourceRefs(
      '/c/programming/p/12\nhttps://howlers.example/c/x/p/12#c-34, 7\n7:9\n/p/12\nnot a link',
    );
    expect(refs).toEqual([{ postId: 12 }, { postId: 12, commentId: 34 }, { postId: 7 }, { postId: 7, commentId: 9 }]);
    expect(invalid).toEqual(['not a link']);
  });
});

describe('countCues', () => {
  it('counts VTT/SRT cues and timestamped lines', () => {
    const vtt = 'WEBVTT\n\n00:00:00.000 --> 00:00:04.000\nHi\n\n00:00:04.000 --> 00:00:08.000\nThere';
    expect(countCues('vtt', vtt)).toBe(2);
    expect(countCues('srt', '1\n00:00:01,000 --> 00:00:02,000\nx')).toBe(1);
    expect(countCues('timestamped', '[00:00:01] A: hi\nno\n[1:02] B: yo')).toBe(2);
  });
});

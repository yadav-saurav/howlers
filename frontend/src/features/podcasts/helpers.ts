import type { EpisodeStatus, EpisodeType, SourceRef } from '@/types/podcasts';

export const EPISODE_TYPES: { value: EpisodeType; label: string }[] = [
  { value: 'WEEKLY_ROUNDUP', label: 'Weekly roundup' },
  { value: 'DEEP_DIVE', label: 'Deep dive' },
  { value: 'DEBATE', label: 'Debate' },
  { value: 'INTERVIEW', label: 'Interview' },
  { value: 'BEGINNER_GUIDE', label: 'Beginner guide' },
  { value: 'MONTHLY_REPORT', label: 'Monthly report' },
  { value: 'COMPARISON', label: 'Comparison' },
  { value: 'HIGHLIGHTS', label: 'Highlights' },
  { value: 'MULTI_PERSPECTIVE', label: 'Multi-perspective' },
];

export const typeLabel = (t: EpisodeType) => EPISODE_TYPES.find((x) => x.value === t)?.label ?? t;

export const STATUS_LABEL: Record<EpisodeStatus, string> = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In review',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
};

/**
 * Parse "45", "1:05", "01:02:03" or "1:02:03.5" into seconds. Returns null when invalid
 * (minutes/seconds parts must be < 60 once an hour/minute part precedes them).
 */
export function parseTimestamp(input: string): number | null {
  const s = input.trim();
  if (!s) return null;
  if (!/^\d+(:\d{1,2}){0,2}([.,]\d+)?$/.test(s)) return null;
  const [main, frac] = s.split(/[.,]/);
  const parts = main.split(':').map(Number);
  if (parts.slice(1).some((p) => p >= 60)) return null;
  let total = 0;
  for (const p of parts) total = total * 60 + p;
  return frac ? total + Number(`0.${frac}`) : total;
}

/** 63 → "1:03", 3723 → "1:02:03". */
export function formatClock(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return '0:00';
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return `${h > 0 ? `${h}:` : ''}${mm}:${String(sec).padStart(2, '0')}`;
}

/** Human duration: 3723 → "1 h 2 min", 2700 → "45 min", 40 → "1 min". */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds <= 0) return '—';
  const totalMin = Math.max(1, Math.round(seconds / 60));
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

/**
 * Index of the last item whose start <= t (binary search on items sorted by start).
 * Returns -1 when t is before the first item.
 */
export function activeIndex<T>(items: T[], t: number, start: (x: T) => number): number {
  let lo = 0;
  let hi = items.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (start(items[mid]) <= t) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

/** Segment active at time t: last started segment, only while t is before its end. */
export function activeSegmentIndex(segments: { startSeconds: number; endSeconds: number }[], t: number): number {
  const i = activeIndex(segments, t, (s) => s.startSeconds);
  if (i < 0) return -1;
  return t <= segments[i].endSeconds + 0.25 ? i : -1;
}

/**
 * Turn pasted post references into source refs. Accepts one per line or comma-separated:
 * "/c/x/p/12", "https://host/c/x/p/12#c-34", "/p/12", "12", "12#34", "12:34".
 * Duplicates are dropped; invalid lines are returned in `invalid`.
 */
export function parseSourceRefs(input: string): { refs: SourceRef[]; invalid: string[] } {
  const refs: SourceRef[] = [];
  const invalid: string[] = [];
  const seen = new Set<string>();
  for (const raw of input.split(/[\n,]+/)) {
    const s = raw.trim();
    if (!s) continue;
    let m = s.match(/\/p\/(\d+)(?:[^#]*#c-(\d+))?/);
    if (!m) m = s.match(/^(\d+)(?:\s*[#:]\s*(\d+))?$/);
    if (!m) {
      invalid.push(s);
      continue;
    }
    const ref: SourceRef = { postId: Number(m[1]) };
    if (m[2]) ref.commentId = Number(m[2]);
    const key = `${ref.postId}:${ref.commentId ?? 0}`;
    if (seen.has(key)) continue;
    seen.add(key);
    refs.push(ref);
  }
  return { refs, invalid };
}

/** Rough count of transcript cues the server will create; used only for a preview. */
export function countCues(format: 'vtt' | 'srt' | 'timestamped', content: string): number {
  if (format === 'timestamped') return content.split(/\r?\n/).filter((l) => /^\s*\[\d+(:\d{1,2}){0,2}([.,]\d+)?]/.test(l)).length;
  return (content.match(/\d+:\d{2}(:\d{2})?[.,]\d{3}\s*-->/g) ?? []).length;
}

/** Share of an episode listened to, 0..100. */
export function progressPercent(position: number | undefined, duration: number | null | undefined): number {
  if (!position || !duration || duration <= 0) return 0;
  return Math.min(100, Math.max(0, (position / duration) * 100));
}

/** Measure a media file's duration in the browser before upload. */
export function measureDuration(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const el = document.createElement(file.type.startsWith('video') ? 'video' : 'audio');
    el.preload = 'metadata';
    const done = () => URL.revokeObjectURL(url);
    el.onloadedmetadata = () => {
      const d = el.duration;
      done();
      if (Number.isFinite(d) && d > 0) resolve(d);
      else reject(new Error('Could not read the file duration'));
    };
    el.onerror = () => {
      done();
      reject(new Error('This file could not be read as audio or video'));
    };
    el.src = url;
  });
}

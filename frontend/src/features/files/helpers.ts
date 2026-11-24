import type { AccessEvent, LinkStatus, ScanEngine, ShareLink } from '@/types/files';

/**
 * What the server checked on an accepted plaintext file (D-029). A structural check is never called an antivirus scan.
 */
export function scanSummary(engine: ScanEngine | null | undefined): { badge: string; text: string } {
  return engine === 'CLAMAV'
    ? { badge: 'Scanned', text: 'Scanned by ClamAV: no known malware found. Still, only open files from people you trust.' }
    : { badge: 'Checked', text: 'Checked: passed type and structure checks. Not an antivirus scan.' };
}

export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '—';
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v >= 100 || Number.isInteger(v) ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}

/** 0..1, clamped. */
export function usageFraction(used: number, quota: number): number {
  if (quota <= 0) return 1;
  return Math.min(1, Math.max(0, used / quota));
}

export type ExpiryPreset = 'never' | '1h' | '1d' | '7d' | '30d';

export const EXPIRY_PRESETS: { value: ExpiryPreset; label: string }[] = [
  { value: '1h', label: '1 hour' },
  { value: '1d', label: '1 day' },
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
  { value: 'never', label: 'Never' },
];

export function expiryFromPreset(p: ExpiryPreset, now: number = Date.now()): string | null {
  const hours = { never: 0, '1h': 1, '1d': 24, '7d': 24 * 7, '30d': 24 * 30 }[p];
  return hours ? new Date(now + hours * 3_600_000).toISOString() : null;
}

export const STATUS_TEXT: Record<LinkStatus, string> = {
  ACTIVE: 'Active',
  EXPIRED: 'Expired',
  REVOKED: 'Turned off',
  EXHAUSTED: 'Download limit reached',
};

export const EVENT_TEXT: Record<AccessEvent, string> = {
  VIEWED: 'Opened the link',
  DOWNLOADED: 'Downloaded',
  WRONG_PASSWORD: 'Wrong password',
  LOCKED: 'Blocked after too many wrong passwords',
  EXPIRED: 'Tried after it expired',
  REVOKED: 'Tried after you turned it off',
  LIMIT_REACHED: 'Tried after the download limit',
  FILE_DELETED: 'Tried after the file was deleted',
};

export function isFailureEvent(e: AccessEvent): boolean {
  return e !== 'VIEWED' && e !== 'DOWNLOADED';
}

export function downloadsText(l: Pick<ShareLink, 'downloadCount' | 'maxDownloads'>): string {
  return l.maxDownloads == null ? `${l.downloadCount} download${l.downloadCount === 1 ? '' : 's'}` : `${l.downloadCount} of ${l.maxDownloads} downloads`;
}

/** The public share URL. For key-in-link E2EE shares the key goes in the #fragment, which browsers never send to servers. */
export function shareUrl(origin: string, token: string, key?: string | null): string {
  return `${origin}/s/${encodeURIComponent(token)}${key ? `#k=${key}` : ''}`;
}

/** A short, human label for a MIME type. */
export function typeLabel(contentType: string, name = ''): string {
  const ext = name.includes('.') ? name.split('.').pop()!.toUpperCase() : '';
  if (contentType === 'application/octet-stream') return ext || 'File';
  if (contentType === 'application/pdf') return 'PDF';
  if (contentType.startsWith('image/')) return `${ext || 'Image'} image`;
  if (contentType.startsWith('audio/')) return `${ext || 'Audio'} audio`;
  if (contentType.startsWith('video/')) return `${ext || 'Video'} video`;
  if (contentType.startsWith('text/')) return ext ? `${ext} text` : 'Text';
  if (/zip|x-7z|rar|gzip|x-tar|bzip|x-xz/.test(contentType)) return ext ? `${ext} archive` : 'Archive';
  if (/word|opendocument\.text|msword|rtf/.test(contentType)) return 'Document';
  if (/sheet|excel|csv/.test(contentType)) return 'Spreadsheet';
  if (/presentation|powerpoint/.test(contentType)) return 'Presentation';
  return ext || contentType;
}

/** Passphrase rules for E2EE links: long enough to resist offline guessing against the wrapped key. */
export function passphraseProblem(p: string, confirm: string): string | null {
  if (p.length < 10) return 'Use at least 10 characters (a few random words work well).';
  if (p !== confirm) return "The passphrases don't match.";
  return null;
}

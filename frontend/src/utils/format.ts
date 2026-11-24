import clsx, { type ClassValue } from 'clsx';

export function cn(...classes: ClassValue[]): string {
  return clsx(classes);
}

export function timeAgo(iso: string, now: number = Date.now()): string {
  const diff = Math.max(0, now - new Date(iso).getTime());
  const s = Math.floor(diff / 1000);
  if (s < 45) return 'just now';
  const m = Math.floor(s / 60);
  if (m < 60) return `${Math.max(1, m)}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}d`;
  const mo = Math.floor(d / 30);
  if (mo < 12) return `${mo}mo`;
  return `${Math.floor(d / 365)}y`;
}

export function timeUntil(iso: string, now: number = Date.now()): string {
  const m = Math.max(0, Math.round((new Date(iso).getTime() - now) / 60_000));
  if (m < 60) return `${Math.max(1, m)}m`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h}h`;
  return `${Math.round(h / 24)}d`;
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function compactNumber(n: number): string {
  const abs = Math.abs(n);
  if (abs < 1000) return String(n);
  if (abs < 10_000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  if (abs < 1_000_000) return `${Math.round(n / 1000)}k`;
  return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}m`;
}

export function pluralize(n: number, one: string, many = `${one}s`): string {
  return `${compactNumber(n)} ${n === 1 ? one : many}`;
}

export function displayName(u: { username: string; displayName: string | null } | null): string {
  if (!u) return '[deleted]';
  return u.displayName?.trim() || u.username;
}

export function absoluteUrl(path: string): string {
  return `${window.location.origin}${path}`;
}

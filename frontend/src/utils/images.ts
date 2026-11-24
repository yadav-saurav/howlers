import type { Thumbnails } from '@/types/api';

/**
 * Responsive image helpers (D-024). The API stores 320 px and 960 px wide variants next to uploaded PNG/JPEG
 * images. Media DTOs list them in `thumbnails`; plain image URLs (avatars, icons, banners) can ask for one with
 * `?w=320|960`, and the server falls back to the original when no variant exists.
 */

const SIZED_PREFIXES = ['/api/v1/files/', '/api/v1/dm-media/'];

/** `url?w=width` for our own media URLs; any other URL (external, blob:, data:) is returned unchanged. */
export function sizedUrl(url: string | null | undefined, width: 320 | 960): string | undefined {
  if (!url) return undefined;
  if (!SIZED_PREFIXES.some((p) => url.startsWith(p))) return url;
  if (url.includes('?')) return url;
  return `${url}?w=${width}`;
}

export interface ResponsiveImage {
  src: string;
  srcSet?: string;
}

/**
 * `src` + `srcSet` with width descriptors for an image whose variants are known. The original is listed with its real
 * width when known; without variants the plain URL is returned, so older uploads still render.
 */
export function responsiveImage(m: { url: string; width: number | null; thumbnails?: Thumbnails | null }): ResponsiveImage {
  const t = m.thumbnails;
  if (!t || (!t.w320 && !t.w960)) return { src: m.url };
  const parts: string[] = [];
  if (t.w320) parts.push(`${t.w320} 320w`);
  if (t.w960) parts.push(`${t.w960} 960w`);
  if (m.width && m.width > 960) parts.push(`${m.url} ${m.width}w`);
  else if (!t.w960 && m.width && m.width > 320) parts.push(`${m.url} ${m.width}w`);
  // The 960 px variant is a sensible fallback src; browsers with srcset support pick by layout width.
  return { src: t.w960 ?? t.w320 ?? m.url, srcSet: parts.join(', ') };
}

/** `sizes` for a full-width feed/post image: the content column is at most ~720 px wide. */
export const FEED_IMAGE_SIZES = '(min-width: 768px) 720px, 100vw';

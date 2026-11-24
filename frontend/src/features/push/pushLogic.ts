import type { PushCategory } from '@/types/push';

/** base64url (as the server sends the VAPID key) → bytes for PushManager.subscribe. */
export function base64UrlToBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
  const raw = atob(padded);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

export function bytesEqual(a: ArrayBuffer | null | undefined, b: Uint8Array): boolean {
  if (!a) return false;
  const x = new Uint8Array(a);
  return x.length === b.length && x.every((v, i) => v === b[i]);
}

/** A readable device name from the user agent ("Firefox on Android"). Only the label is stored, not the UA. */
export function deviceLabel(ua: string): string {
  const browser = /Edg\//.test(ua) ? 'Edge'
    : /OPR\//.test(ua) ? 'Opera'
      : /SamsungBrowser\//.test(ua) ? 'Samsung Internet'
        : /Firefox\//.test(ua) ? 'Firefox'
          : /Chrome\//.test(ua) ? 'Chrome'
            : /Safari\//.test(ua) ? 'Safari'
              : 'Browser';
  const os = /Android/.test(ua) ? 'Android'
    : /iPhone|iPad|iPod/.test(ua) ? 'iOS'
      : /Windows/.test(ua) ? 'Windows'
        : /Mac OS X|Macintosh/.test(ua) ? 'macOS'
          : /CrOS/.test(ua) ? 'ChromeOS'
            : /Linux/.test(ua) ? 'Linux'
              : null;
  return os ? `${browser} on ${os}` : browser;
}

export const CATEGORY_INFO: Record<PushCategory, { label: string; description: string }> = {
  REPLIES: { label: 'Replies', description: 'Comments on your posts and replies to your comments.' },
  MENTIONS: { label: 'Mentions', description: 'When someone mentions you with @username.' },
  MESSAGES: { label: 'Direct messages', description: 'Who wrote to you. The message text is never included.' },
  MODERATION: { label: 'Moderation', description: 'Removals, bans, appeal results and role changes that affect you.' },
  FOLLOWERS: { label: 'New followers', description: 'When someone starts following you.' },
  ACTIVITY: { label: 'Activity', description: 'Post milestones and resolved predictions.' },
};

export type PushSupport =
  | { ok: true }
  | { ok: false; reason: 'unsupported' | 'insecure' | 'no-worker' | 'ios-needs-install' };

/** What this browser can do, independent of the server. */
export function browserSupport(env: {
  isSecureContext: boolean;
  hasServiceWorker: boolean;
  hasPushManager: boolean;
  hasNotification: boolean;
  workerEnabled: boolean;
  ua: string;
  standalone: boolean;
}): PushSupport {
  if (!env.isSecureContext) return { ok: false, reason: 'insecure' };
  const ios = /iPhone|iPad|iPod/.test(env.ua);
  if (ios && !env.standalone && !env.hasPushManager) return { ok: false, reason: 'ios-needs-install' };
  if (!env.hasServiceWorker || !env.hasPushManager || !env.hasNotification) return { ok: false, reason: 'unsupported' };
  if (!env.workerEnabled) return { ok: false, reason: 'no-worker' };
  return { ok: true };
}

export const SUPPORT_MESSAGES: Record<Exclude<PushSupport, { ok: true }>['reason'], string> = {
  unsupported: 'This browser doesn’t support push notifications.',
  insecure: 'Push notifications need a secure (HTTPS) connection.',
  'no-worker': 'Push needs the Howlers service worker, which runs in production builds. In local development, start Vite with VITE_ENABLE_SW_IN_DEV=true to try it.',
  'ios-needs-install': 'On iPhone and iPad, add Howlers to your Home Screen first (Share → Add to Home Screen), then open it from there to turn on notifications.',
};

/** localStorage key remembering which device row this browser is, per signed-in user. */
export const deviceKey = (userId: number) => `howlers-push-device:${userId}`;

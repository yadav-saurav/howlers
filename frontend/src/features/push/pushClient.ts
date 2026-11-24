import { getRegistration, swEnabled } from '@/features/pwa/serviceWorker';
import type { PushCategory, PushDevice } from '@/types/push';
import { pushApi } from './api';
import { base64UrlToBytes, browserSupport, bytesEqual, deviceKey, deviceLabel, type PushSupport } from './pushLogic';

export function currentSupport(): PushSupport {
  const nav = typeof navigator === 'undefined' ? null : navigator;
  return browserSupport({
    isSecureContext: typeof window !== 'undefined' && window.isSecureContext,
    hasServiceWorker: !!nav && 'serviceWorker' in nav,
    hasPushManager: typeof window !== 'undefined' && 'PushManager' in window,
    hasNotification: typeof window !== 'undefined' && 'Notification' in window,
    workerEnabled: swEnabled,
    ua: nav?.userAgent ?? '',
    standalone: typeof window !== 'undefined' && window.matchMedia?.('(display-mode: standalone)').matches,
  });
}

export function rememberedDevice(userId: number): number | null {
  try {
    const v = localStorage.getItem(deviceKey(userId));
    return v ? Number(v) : null;
  } catch {
    return null;
  }
}

function remember(userId: number, id: number | null) {
  try {
    if (id == null) localStorage.removeItem(deviceKey(userId));
    else localStorage.setItem(deviceKey(userId), String(id));
  } catch {
    /* storage blocked: the device just won't be recognised after a reload */
  }
}

async function browserSubscription(): Promise<PushSubscription | null> {
  const reg = await getRegistration();
  return reg ? reg.pushManager.getSubscription() : null;
}

function body(sub: PushSubscription, categories?: PushCategory[]) {
  const json = sub.toJSON();
  if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) throw new Error('The browser returned an incomplete subscription');
  return { endpoint: json.endpoint, keys: { p256dh: json.keys.p256dh, auth: json.keys.auth }, label: deviceLabel(navigator.userAgent), categories };
}

/** Asks for permission (on a click), subscribes this browser and registers it for the signed-in user. */
export async function enablePush(userId: number, publicKey: string): Promise<PushDevice> {
  const reg = await getRegistration();
  if (!reg) throw new Error('The Howlers service worker isn’t running in this tab. Reload the page and try again.');
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    throw new Error(permission === 'denied'
      ? 'Notifications are blocked for this site. Allow them in your browser’s site settings, then try again.'
      : 'Notification permission wasn’t granted.');
  }
  const key = base64UrlToBytes(publicKey);
  let sub = await reg.pushManager.getSubscription();
  // A subscription made with an older server key can't receive pushes any more: replace it.
  if (sub && !bytesEqual(sub.options.applicationServerKey, key)) {
    await sub.unsubscribe();
    sub = null;
  }
  sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  const device = await pushApi.subscribe(body(sub));
  remember(userId, device.id);
  return device;
}

/** Turns push off for this browser: removes the server row and the browser subscription. */
export async function disablePush(userId: number, deviceId: number | null) {
  const sub = await browserSubscription();
  if (deviceId != null) await pushApi.remove(deviceId).catch(() => undefined);
  else if (sub) await pushApi.unsubscribeEndpoint(sub.endpoint).catch(() => undefined);
  await sub?.unsubscribe().catch(() => undefined);
  remember(userId, null);
}

/**
 * On sign-in/boot: if this user turned push on in this browser before and permission is still granted, re-send the
 * current subscription (browsers rotate them). Nothing happens for users who never opted in here.
 */
export async function syncPush(userId: number, publicKey: string): Promise<PushDevice | null> {
  if (rememberedDevice(userId) == null || Notification.permission !== 'granted') return null;
  const sub = await browserSubscription();
  if (!sub || !bytesEqual(sub.options.applicationServerKey, base64UrlToBytes(publicKey))) {
    remember(userId, null);
    return null;
  }
  const device = await pushApi.subscribe(body(sub));
  remember(userId, device.id);
  return device;
}

/** Before signing out: this browser stops receiving the account's pushes (best effort, bounded). */
export async function releasePushOnSignOut(userId: number | undefined) {
  if (userId == null || currentSupport().ok === false) return;
  const work = (async () => {
    const sub = await browserSubscription();
    if (!sub) return;
    await pushApi.unsubscribeEndpoint(sub.endpoint).catch(() => undefined);
    await sub.unsubscribe().catch(() => undefined);
    remember(userId, null);
  })();
  await Promise.race([work.catch(() => undefined), new Promise((r) => setTimeout(r, 2500))]);
}

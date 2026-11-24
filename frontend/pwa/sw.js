/* Howlers service worker (D-039). Built by pwa/serviceWorkerPlugin.ts, which prepends
 *   const SW_CONFIG = { version, precache: [...], dev };
 *
 * What it caches: the offline page, icons and the hashed static files under /assets/ (immutable, so cache-first is
 * safe). What it never touches: /api/, /ws, uploads and anything cross-origin; responses with personal data stay
 * network-only and are never stored. Page navigations go to the network; when that fails, the offline page is shown.
 * A new version waits until the user accepts the update prompt (SKIP_WAITING), so a page never runs against a
 * service worker from a different build mid-session.
 */
/* global SW_CONFIG */
const CACHE = `howlers-static-${SW_CONFIG.version}`;
const OFFLINE_URL = '/offline.html';

self.addEventListener('install', (event) => {
  if (SW_CONFIG.dev) return;
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SW_CONFIG.precache)));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k.startsWith('howlers-static-') && k !== CACHE).map((k) => caches.delete(k))),
    ).then(() => self.clients.claim()),
  );
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') self.skipWaiting();
});

/** Static files this worker may serve from cache. Everything else is left to the browser. */
function isStaticAsset(url) {
  return url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/') || url.pathname === '/favicon.svg'
    || url.pathname === '/manifest.webmanifest' || url.pathname === OFFLINE_URL;
}

/** Paths that must always go to the network untouched. */
function isPrivate(url) {
  return url.pathname.startsWith('/api/') || url.pathname === '/ws' || url.pathname.startsWith('/ws/')
    || url.pathname.startsWith('/swagger-ui') || url.pathname.startsWith('/v3/api-docs');
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || isPrivate(url)) return;

  if (req.mode === 'navigate') {
    // Network only (pages can be personalised); the offline page when the network is unreachable.
    event.respondWith(
      fetch(req).catch(() => caches.match(OFFLINE_URL).then((r) => r || new Response('You are offline', {
        status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      }))),
    );
    return;
  }

  if (SW_CONFIG.dev || !isStaticAsset(url)) return;
  // Cache-first for hashed build output and icons; fill the cache on first use.
  event.respondWith(
    caches.match(req).then((hit) => hit || fetch(req).then((res) => {
      if (res.ok && res.type === 'basic') {
        const copy = res.clone();
        caches.open(CACHE).then((cache) => cache.put(req, copy));
      }
      return res;
    })),
  );
});

// ------------------------------------------------------------------ web push (D-040)

/** Only same-origin relative links are opened from a notification. */
function safePath(value) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : '/notifications';
}

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = {};
  }
  const title = typeof data.title === 'string' && data.title ? data.title.slice(0, 80) : 'Howlers';
  const body = typeof data.body === 'string' ? data.body.slice(0, 300) : 'You have a new notification.';
  const tag = typeof data.tag === 'string' ? data.tag.slice(0, 120) : undefined;
  event.waitUntil(self.registration.showNotification(title, {
    body,
    tag,
    renotify: Boolean(tag),
    icon: '/icons/icon-192.png',
    badge: '/icons/badge-72.png',
    data: { url: safePath(data.url) },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = new URL(safePath(event.notification.data && event.notification.data.url), self.location.origin).href;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(async (windows) => {
      for (const w of windows) {
        if (new URL(w.url).origin !== self.location.origin) continue;
        await w.focus();
        try {
          await w.navigate(target);
        } catch {
          w.postMessage({ type: 'NAVIGATE', url: target });
        }
        return;
      }
      await self.clients.openWindow(target);
    }),
  );
});

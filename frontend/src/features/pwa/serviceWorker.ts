import { create } from 'zustand';

/**
 * Service worker registration (D-039). Registered in production builds only, unless VITE_ENABLE_SW_IN_DEV=true, so
 * the Vite dev server (HMR, proxies) is never served from a cache. A new version waits until the user accepts the
 * update prompt.
 */
export const swEnabled = import.meta.env.PROD || import.meta.env.VITE_ENABLE_SW_IN_DEV === 'true';

interface SwState {
  /** A new build is installed and waiting. */
  waiting: ServiceWorker | null;
  dismissed: boolean;
  dismiss: () => void;
}

export const useServiceWorker = create<SwState>((set) => ({
  waiting: null,
  dismissed: false,
  dismiss: () => set({ dismissed: true }),
}));

let registration: Promise<ServiceWorkerRegistration | null> = Promise.resolve(null);

/** The active registration, or null when the worker isn't used (dev, unsupported browser). */
export function getRegistration(): Promise<ServiceWorkerRegistration | null> {
  return registration;
}

function track(reg: ServiceWorkerRegistration) {
  const offer = (w: ServiceWorker | null) => {
    // Only an update (a controller already exists), not the very first install.
    if (w && navigator.serviceWorker.controller) useServiceWorker.setState({ waiting: w, dismissed: false });
  };
  if (reg.waiting) offer(reg.waiting);
  reg.addEventListener('updatefound', () => {
    const w = reg.installing;
    w?.addEventListener('statechange', () => {
      if (w.state === 'installed') offer(w);
    });
  });
}

export function registerServiceWorker() {
  if (!swEnabled || typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return;
  registration = new Promise((resolve) => {
    window.addEventListener('load', () => {
      navigator.serviceWorker
        .register('/sw.js', { scope: '/' })
        .then((reg) => {
          track(reg);
          // Look for a new version hourly while the app stays open.
          window.setInterval(() => void reg.update().catch(() => undefined), 60 * 60 * 1000);
          resolve(reg);
        })
        .catch(() => resolve(null));
    });
  });
  let reloading = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (reloading || !useServiceWorker.getState().waiting) return;
    reloading = true;
    window.location.reload();
  });
  // Notification clicks fall back to asking the page to navigate when the worker can't.
  navigator.serviceWorker.addEventListener('message', (e: MessageEvent) => {
    const data = e.data as { type?: string; url?: string } | null;
    if (data?.type === 'NAVIGATE' && typeof data.url === 'string') {
      const url = new URL(data.url, window.location.origin);
      if (url.origin === window.location.origin) window.location.assign(url.pathname + url.search + url.hash);
    }
  });
}

/** Activates the waiting version; the page reloads once it takes control. */
export function applyUpdate() {
  useServiceWorker.getState().waiting?.postMessage({ type: 'SKIP_WAITING' });
}

import swSource from '../../../pwa/sw.js?raw';

/** Runs pwa/sw.js against a fake ServiceWorkerGlobalScope and records what it does with each event. */
function loadWorker(config = { version: 't1', precache: ['/offline.html', '/assets/index-abc.js'], dev: false }) {
  const handlers: Record<string, (e: unknown) => void> = {};
  const store = new Map<string, Response>();
  const shown: { title: string; options: NotificationOptions & { data?: { url: string } } }[] = [];
  const opened: string[] = [];
  const self = {
    location: { origin: 'https://howlers.test' },
    addEventListener: (type: string, fn: (e: unknown) => void) => {
      handlers[type] = fn;
    },
    skipWaiting: vi.fn(),
    clients: {
      claim: vi.fn(async () => undefined),
      matchAll: vi.fn(async () => [] as unknown[]),
      openWindow: vi.fn(async (url: string) => {
        opened.push(url);
      }),
    },
    registration: {
      showNotification: vi.fn(async (title: string, options: NotificationOptions) => {
        shown.push({ title, options });
      }),
    },
  };
  const caches = {
    open: async () => ({
      addAll: async (urls: string[]) => urls.forEach((u) => store.set(u, new Response(`cached ${u}`))),
      put: async (req: Request, res: Response) => void store.set(new URL(req.url).pathname, res),
    }),
    match: async (req: Request | string) => store.get(typeof req === 'string' ? req : new URL(req.url).pathname),
    keys: async () => ['howlers-static-old', 'howlers-static-t1', 'other-app'],
    delete: vi.fn(async () => true),
  };
  // Same-origin responses in a real worker are type "basic"; a constructed Response says "default".
  const network = vi.fn(async (req: Request) => {
    const res = new Response(`network ${new URL(req.url).pathname}`);
    Object.defineProperty(res, 'type', { value: 'basic' });
    return res;
  });
  new Function('self', 'caches', 'fetch', 'SW_CONFIG', swSource)(self, caches, network, config);

  /** Dispatches a fetch; resolves to the response text, or null when the worker left the request alone. */
  async function request(path: string, init: { mode?: RequestMode; method?: string } = {}) {
    let responded: Promise<Response> | null = null;
    const request = { url: `https://howlers.test${path}`, method: init.method ?? 'GET', mode: init.mode ?? 'cors' } as Request;
    handlers.fetch({ request, respondWith: (p: Promise<Response>) => (responded = p) });
    if (!responded) return null;
    return (await responded as Response).text();
  }
  return { handlers, store, shown, opened, self, caches, network, request };
}

describe('service worker', () => {
  it('precaches the shell on install and drops old caches on activate', async () => {
    const w = loadWorker();
    let wait: Promise<unknown> = Promise.resolve();
    w.handlers.install({ waitUntil: (p: Promise<unknown>) => (wait = p) });
    await wait;
    expect([...w.store.keys()]).toEqual(['/offline.html', '/assets/index-abc.js']);
    w.handlers.activate({ waitUntil: (p: Promise<unknown>) => (wait = p) });
    await wait;
    expect(w.caches.delete).toHaveBeenCalledTimes(1);
    expect(w.caches.delete).toHaveBeenCalledWith('howlers-static-old');
  });

  it('never touches API calls, sockets, other origins or non-GET requests', async () => {
    const w = loadWorker();
    expect(await w.request('/api/v1/users/me')).toBeNull();
    expect(await w.request('/api/v1/notifications', { mode: 'navigate' })).toBeNull();
    expect(await w.request('/ws')).toBeNull();
    expect(await w.request('/assets/index-abc.js', { method: 'POST' })).toBeNull();
    const handled = { called: false };
    w.handlers.fetch({ request: { url: 'https://cdn.example/x.js', method: 'GET', mode: 'cors' }, respondWith: () => (handled.called = true) });
    expect(handled.called).toBe(false);
    expect(w.network).not.toHaveBeenCalled();
  });

  it('serves hashed assets cache-first and caches them on first use', async () => {
    const w = loadWorker();
    expect(await w.request('/assets/lazy-xyz.js')).toBe('network /assets/lazy-xyz.js');
    await new Promise((r) => setTimeout(r, 0));
    expect(w.store.has('/assets/lazy-xyz.js')).toBe(true);
    w.network.mockClear();
    expect(await w.request('/assets/lazy-xyz.js')).toBe('network /assets/lazy-xyz.js'); // the stored copy
    expect(w.network).not.toHaveBeenCalled();
  });

  it('sends navigations to the network and shows the offline page when it fails', async () => {
    const w = loadWorker();
    w.store.set('/offline.html', new Response('offline page'));
    expect(await w.request('/c/moon', { mode: 'navigate' })).toBe('network /c/moon');
    expect(w.store.has('/c/moon')).toBe(false); // pages are never stored
    w.network.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    expect(await w.request('/settings', { mode: 'navigate' })).toBe('offline page');
  });

  it('does no caching at all in dev mode', async () => {
    const w = loadWorker({ version: 'dev', precache: [], dev: true });
    expect(await w.request('/assets/index-abc.js')).toBeNull();
  });

  it('shows pushes and opens only same-origin paths on click', async () => {
    const w = loadWorker();
    let wait: Promise<unknown> = Promise.resolve();
    w.handlers.push({
      data: { json: () => ({ title: 'Howlers', body: 'u/ana replied to your comment', url: '/p/1#c-2', tag: 'comment-reply:3' }) },
      waitUntil: (p: Promise<unknown>) => (wait = p),
    });
    await wait;
    expect(w.shown[0].title).toBe('Howlers');
    expect(w.shown[0].options).toMatchObject({ body: 'u/ana replied to your comment', tag: 'comment-reply:3', data: { url: '/p/1#c-2' } });

    w.handlers.push({ data: { json: () => ({ url: 'https://evil.example/phish' }) }, waitUntil: (p: Promise<unknown>) => (wait = p) });
    await wait;
    expect(w.shown[1].options.data?.url).toBe('/notifications');

    const close = vi.fn();
    w.handlers.notificationclick({ notification: { close, data: { url: '//evil.example/x' } }, waitUntil: (p: Promise<unknown>) => (wait = p) });
    await wait;
    expect(close).toHaveBeenCalled();
    expect(w.opened).toEqual(['https://howlers.test/notifications']);
  });

  it('activates a waiting update only when asked', () => {
    const w = loadWorker();
    w.handlers.message({ data: { type: 'HELLO' } });
    expect(w.self.skipWaiting).not.toHaveBeenCalled();
    w.handlers.message({ data: { type: 'SKIP_WAITING' } });
    expect(w.self.skipWaiting).toHaveBeenCalled();
  });
});

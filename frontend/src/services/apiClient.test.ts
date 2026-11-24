import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/store/authStore';
import { api, buildUrl } from './apiClient';
import { ApiError } from './apiError';

const me = {
  id: 1, username: 'ada', email: 'a@x.io', displayName: null, avatarUrl: null, bannerUrl: null, bio: null,
  emailVerified: true, onboardingCompleted: true, role: 'USER', postKarma: 0, commentKarma: 0, createdAt: '2026-01-01T00:00:00Z',
  settings: { theme: 'dark', showNsfw: false, profilePublic: true, showCommunities: true, emailNotifications: false },
};

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('apiClient', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessToken: 'old', user: null, status: 'authenticated' });
  });
  afterEach(() => vi.restoreAllMocks());

  it('builds URLs and drops empty query values', () => {
    expect(buildUrl('/feed', { tab: 'top', cursor: undefined, t: '', limit: 25 })).toBe('/api/v1/feed?tab=top&limit=25');
  });

  it('refreshes once for concurrent 401s and retries each request with the new token', async () => {
    let refreshCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/auth/refresh')) {
        refreshCalls++;
        await new Promise((r) => setTimeout(r, 10));
        return json(200, { accessToken: 'new', expiresIn: 900, user: me });
      }
      const auth = (init?.headers as Record<string, string>)?.Authorization;
      return auth === 'Bearer new' ? json(200, { ok: url }) : json(401, { status: 401, code: 'UNAUTHORIZED', message: 'expired' });
    });
    vi.stubGlobal('fetch', fetchMock);

    const [a, b, c] = await Promise.all([api<{ ok: string }>('/a'), api<{ ok: string }>('/b'), api<{ ok: string }>('/c')]);
    expect(refreshCalls).toBe(1);
    expect([a.ok, b.ok, c.ok]).toEqual(['/api/v1/a', '/api/v1/b', '/api/v1/c']);
    expect(useAuthStore.getState().accessToken).toBe('new');
    expect(useAuthStore.getState().status).toBe('authenticated');
  });

  it('holds requests made while the session is booting until the boot refresh lands', async () => {
    useAuthStore.setState({ accessToken: null, user: null, status: 'booting' });
    let refreshCalls = 0;
    const seen: (string | undefined)[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/auth/refresh')) {
        refreshCalls++;
        await new Promise((r) => setTimeout(r, 10));
        return json(200, { accessToken: 'boot', expiresIn: 900, user: me });
      }
      seen.push((init?.headers as Record<string, string>)?.Authorization);
      return json(200, { ok: true });
    }));

    await Promise.all([api('/feed'), api('/communities/programming')]);
    expect(refreshCalls).toBe(1);
    expect(seen).toEqual(['Bearer boot', 'Bearer boot']); // never sent signed-out
  });

  it('sends requests without a token once a boot refresh finds no session', async () => {
    useAuthStore.setState({ accessToken: null, user: null, status: 'booting' });
    const seen: (string | undefined)[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/auth/refresh')) return json(401, { status: 401, code: 'UNAUTHORIZED', message: 'none' });
      seen.push((init?.headers as Record<string, string>)?.Authorization);
      return json(200, { ok: true });
    }));
    await api('/feed');
    expect(seen).toEqual([undefined]);
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  it('clears the session and surfaces the 401 when refresh fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) =>
        String(input).endsWith('/auth/refresh')
          ? json(401, { status: 401, code: 'UNAUTHORIZED', message: 'no session' })
          : json(401, { status: 401, code: 'UNAUTHORIZED', message: 'expired' }),
      ),
    );
    await expect(api('/x')).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' });
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('parses the error envelope including field errors and trace id', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        json(400, { status: 400, code: 'VALIDATION_FAILED', message: 'Invalid', fieldErrors: { username: 'taken' }, traceId: 't-1' }),
      ),
    );
    const err = await api('/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).fieldErrors).toEqual({ username: 'taken' });
    expect((err as ApiError).traceId).toBe('t-1');
  });

  it('maps network failures to a NETWORK_ERROR ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    await expect(api('/x')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 });
  });

  it('returns undefined for 204 responses', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })));
    await expect(api('/x', { method: 'POST' })).resolves.toBeUndefined();
  });
});

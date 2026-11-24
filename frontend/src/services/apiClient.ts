import { useAuthStore } from '@/store/authStore';
import type { ApiErrorBody, AuthResponse } from '@/types/api';
import { ApiError } from './apiError';

export const API_BASE = '/api/v1';

export type Query = Record<string, string | number | boolean | null | undefined>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Query;
  /** Skip the automatic refresh-and-retry on 401 (used by auth endpoints). */
  skipRefresh?: boolean;
  signal?: AbortSignal;
}

export function buildUrl(path: string, query?: Query): string {
  const qs = new URLSearchParams();
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') qs.set(k, String(v));
    }
  }
  const s = qs.toString();
  return `${API_BASE}${path}${s ? `?${s}` : ''}`;
}

async function parseError(res: Response): Promise<ApiError> {
  let body: Partial<ApiErrorBody> = {};
  try {
    body = (await res.json()) as ApiErrorBody;
  } catch {
    /* non-JSON error body */
  }
  return new ApiError({
    status: res.status,
    code: body.code ?? (res.status === 401 ? 'UNAUTHORIZED' : 'HTTP_ERROR'),
    message: body.message ?? (res.statusText || 'Request failed'),
    fieldErrors: body.fieldErrors ?? {},
    traceId: body.traceId ?? res.headers.get('X-Trace-Id') ?? undefined,
  });
}

let refreshInFlight: Promise<boolean> | null = null;

/**
 * Single-flight refresh: concurrent 401s (and the boot call) share one POST /auth/refresh.
 * Resolves true when a new access token was obtained; clears the session otherwise.
 */
export function refreshSession(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const res = await fetch(`${API_BASE}/auth/refresh`, { method: 'POST', credentials: 'include' });
        if (!res.ok) {
          useAuthStore.getState().clear();
          return false;
        }
        const data = (await res.json()) as AuthResponse;
        useAuthStore.getState().setSession(data.accessToken, data.user);
        return true;
      } catch {
        useAuthStore.getState().clear();
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

async function send(path: string, opts: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = useAuthStore.getState().accessToken;
  if (token) headers.Authorization = `Bearer ${token}`;
  let body: BodyInit | undefined;
  if (opts.body instanceof FormData) {
    body = opts.body;
  } else if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  try {
    return await fetch(buildUrl(path, opts.query), {
      method: opts.method ?? 'GET',
      headers,
      body,
      credentials: 'include',
      signal: opts.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw ApiError.network();
  }
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  // On a page load the session is restored by one refresh. Requests made before it finishes (React runs page effects
  // before SessionBoot's) would otherwise go out without a token and cache signed-out data (no vote, not joined, no
  // mod rights) that nothing refetches. So they wait for that single shared refresh first.
  if (!opts.skipRefresh && !path.startsWith('/auth/') && useAuthStore.getState().status === 'booting') {
    await refreshSession();
  }
  let res = await send(path, opts);
  if (res.status === 401 && !opts.skipRefresh) {
    const ok = await refreshSession();
    if (ok) res = await send(path, opts);
  }
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const http = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => api<T>(path, { query, signal }),
  post: <T>(path: string, body?: unknown, opts: Omit<RequestOptions, 'body' | 'method'> = {}) =>
    api<T>(path, { ...opts, method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => api<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => api<T>(path, { method: 'PATCH', body }),
  del: <T>(path: string, body?: unknown) => api<T>(path, { method: 'DELETE', body }),
  upload: <T>(path: string, file: File, field = 'file') => {
    const fd = new FormData();
    fd.append(field, file);
    return api<T>(path, { method: 'POST', body: fd });
  },
};

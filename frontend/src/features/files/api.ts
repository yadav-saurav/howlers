import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { API_BASE, http, refreshSession } from '@/services/apiClient';
import { ApiError } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import type { CursorPage } from '@/types/api';
import type {
  AccessEntry,
  CreateLinkBody,
  CreatedLink,
  FileItem,
  FileUsage,
  ShareLink,
  ShareMeta,
  ShareUnlock,
} from '@/types/files';

const f = (id: number) => `/me/files/${id}`;

export const filesApi = {
  list: (q: string, cursor?: string) => http.get<CursorPage<FileItem>>('/me/files', { q: q || undefined, cursor }),
  usage: () => http.get<FileUsage>('/me/files/usage'),
  rename: (id: number, name: string) => http.patch<FileItem>(f(id), { name }),
  remove: (id: number) => http.del<void>(f(id)),
  downloadUrl: (id: number) => http.post<{ url: string; expiresAt: string }>(`${f(id)}/download-url`),
  links: (id: number) => http.get<ShareLink[]>(`${f(id)}/links`),
  createLink: (id: number, body: CreateLinkBody) => http.post<CreatedLink>(`${f(id)}/links`, body),
  revokeLink: (id: number, linkId: number) => http.del<void>(`${f(id)}/links/${linkId}`),
  access: (id: number, linkId: number, cursor?: string) =>
    http.get<CursorPage<AccessEntry>>(`${f(id)}/links/${linkId}/access`, { cursor }),
  shareMeta: (token: string) => http.get<ShareMeta>(`/share/${encodeURIComponent(token)}`),
  unlock: (token: string, password?: string) =>
    http.post<ShareUnlock>(`/share/${encodeURIComponent(token)}/unlock`, { password: password || undefined }),
};

export const fileKeys = {
  all: ['files'] as const,
  list: (q: string) => ['files', 'list', q] as const,
  usage: ['files', 'usage'] as const,
  links: (id: number) => ['files', 'links', id] as const,
  access: (id: number, linkId: number) => ['files', 'access', id, linkId] as const,
  share: (token: string) => ['share', token] as const,
};

export function useFiles(q: string) {
  return useInfiniteQuery({
    queryKey: fileKeys.list(q),
    queryFn: ({ pageParam }) => filesApi.list(q, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: (p) => p,
  });
}

export function useFileUsage() {
  return useQuery({ queryKey: fileKeys.usage, queryFn: filesApi.usage });
}

export function useFileLinks(id: number, enabled = true) {
  return useQuery({ queryKey: fileKeys.links(id), queryFn: () => filesApi.links(id), enabled });
}

export function useAccessLog(id: number, linkId: number, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: fileKeys.access(id, linkId),
    queryFn: ({ pageParam }) => filesApi.access(id, linkId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  });
}

export function useShareMeta(token: string) {
  return useQuery({ queryKey: fileKeys.share(token), queryFn: () => filesApi.shareMeta(token), retry: false, refetchOnWindowFocus: false });
}

/** Every file mutation invalidates the files namespace: lists are per-user and small. */
export function useFileMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: fileKeys.all }) });
}

/**
 * Multipart upload with progress. Uses XHR (fetch has no upload progress) with the same Bearer +
 * single-flight refresh behaviour as the main client.
 */
export async function uploadFile(
  file: Blob,
  fileName: string,
  opts: { e2ee?: boolean; label?: string; onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<FileItem> {
  const attempt = () =>
    new Promise<{ status: number; body: string }>((resolve, reject) => {
      if (opts.signal?.aborted) {
        reject(new DOMException('Upload cancelled', 'AbortError'));
        return;
      }
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${API_BASE}/me/files`);
      xhr.withCredentials = true;
      xhr.setRequestHeader('Accept', 'application/json');
      const token = useAuthStore.getState().accessToken;
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
      xhr.upload.onprogress = (e) => e.lengthComputable && opts.onProgress?.(e.loaded / e.total);
      xhr.onload = () => resolve({ status: xhr.status, body: xhr.responseText });
      xhr.onerror = () => reject(ApiError.network());
      xhr.onabort = () => reject(new DOMException('Upload cancelled', 'AbortError'));
      opts.signal?.addEventListener('abort', () => xhr.abort(), { once: true });
      const fd = new FormData();
      fd.append('file', file, fileName);
      if (opts.e2ee) fd.append('e2ee', 'true');
      if (opts.label) fd.append('label', opts.label);
      xhr.send(fd);
    });
  let res = await attempt();
  if (res.status === 401 && (await refreshSession())) res = await attempt();
  let json: Record<string, unknown> = {};
  try {
    json = res.body ? JSON.parse(res.body) : {};
  } catch {
    /* non-JSON */
  }
  if (res.status < 200 || res.status >= 300) {
    const fieldErrors = (json.fieldErrors as Record<string, string>) ?? {};
    throw new ApiError({
      status: res.status,
      code: (json.code as string) ?? 'HTTP_ERROR',
      message: fieldErrors.file ?? (json.message as string) ?? (res.status === 413 ? 'This file is too large' : 'Upload failed'),
      fieldErrors,
      traceId: json.traceId as string | undefined,
    });
  }
  return json as unknown as FileItem;
}

/** Fetches a file the signed-in owner can read (Bearer auth), as a Blob. Used for E2EE files, which are decrypted locally. */
export async function fetchOwnContent(id: number, onProgress?: (fraction: number) => void, range?: string): Promise<Blob> {
  const get = () => {
    const token = useAuthStore.getState().accessToken;
    const headers: Record<string, string> = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (range) headers.Range = range;
    return fetch(`${API_BASE}${f(id)}/content`, { headers, credentials: 'include' });
  };
  let res = await get();
  if (res.status === 401 && (await refreshSession())) res = await get();
  if (!res.ok) throw new ApiError({ status: res.status, code: 'HTTP_ERROR', message: 'Could not download the file' });
  return readWithProgress(res, onProgress);
}

/** Opens the signed owner's content as a streaming Response (large E2EE files are decrypted to disk as they arrive). */
export async function openOwnContentResponse(id: number): Promise<Response> {
  const get = () => {
    const token = useAuthStore.getState().accessToken;
    return fetch(`${API_BASE}${f(id)}/content`, { headers: token ? { Authorization: `Bearer ${token}` } : {}, credentials: 'include' });
  };
  let res = await get();
  if (res.status === 401 && (await refreshSession())) res = await get();
  if (!res.ok) throw new ApiError({ status: res.status, code: 'HTTP_ERROR', message: 'Could not download the file' });
  return res;
}

/** Opens a signed download URL as a streaming Response, without reading the body. */
export async function openDownloadResponse(url: string): Promise<Response> {
  const res = await fetch(url, { credentials: 'omit' });
  if (!res.ok) {
    let message = 'Could not download the file';
    try {
      message = ((await res.json()) as { message?: string }).message ?? message;
    } catch {
      /* not JSON */
    }
    throw new ApiError({ status: res.status, code: 'HTTP_ERROR', message });
  }
  return res;
}

/** Fetches a signed download URL as a Blob (for E2EE shares, decrypted in the browser). */
export async function fetchDownload(url: string, onProgress?: (fraction: number) => void): Promise<Blob> {
  return readWithProgress(await openDownloadResponse(url), onProgress);
}

async function readWithProgress(res: Response, onProgress?: (fraction: number) => void): Promise<Blob> {
  const total = Number(res.headers.get('Content-Length') ?? 0);
  if (!res.body || !onProgress || !total) return res.blob();
  const reader = res.body.getReader();
  const parts: BlobPart[] = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(value);
    loaded += value.length;
    onProgress(Math.min(1, loaded / total));
  }
  return new Blob(parts, { type: 'application/octet-stream' });
}

/** Starts a normal browser download of a same-origin URL (the server marks every file as an attachment). */
export function triggerDownload(url: string, fileName?: string) {
  const a = document.createElement('a');
  a.href = url;
  if (fileName) a.download = fileName;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
}

/** Saves a Blob produced in the browser (decrypted E2EE files). */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  triggerDownload(url, fileName);
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

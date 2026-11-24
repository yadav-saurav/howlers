import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { API_BASE, http, refreshSession } from '@/services/apiClient';
import { ApiError } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import type { CursorPage } from '@/types/api';
import type {
  AudioGeneration,
  AudioJob,
  EpisodeDetail,
  EpisodePatch,
  EpisodeSummary,
  EpisodeType,
  PodcastCapabilities,
  PodcastHome,
  TranscriptFormat,
  TranscriptSegment,
} from '@/types/podcasts';

const ep = (id: number) => `/podcasts/episodes/${id}`;

export interface ListParams {
  type?: EpisodeType | '';
  community?: string;
  sort?: 'recent' | 'popular';
}

export const podcastApi = {
  capabilities: () => http.get<PodcastCapabilities>('/podcasts/capabilities'),
  home: () => http.get<PodcastHome>('/podcasts/home'),
  list: (p: ListParams, cursor?: string) =>
    http.get<CursorPage<EpisodeSummary>>('/podcasts', { type: p.type || undefined, community: p.community || undefined, sort: p.sort, cursor }),
  history: (cursor?: string) => http.get<CursorPage<EpisodeSummary>>('/me/podcasts/history', { cursor }),
  get: (id: number) => http.get<EpisodeDetail>(ep(id)),
  transcript: (id: number, q?: string) => http.get<TranscriptSegment[]>(`${ep(id)}/transcript`, { q: q || undefined }),
  progress: (id: number, positionSeconds: number, completed?: boolean) =>
    http.put<void>(`${ep(id)}/progress`, { positionSeconds: Math.max(0, Math.round(positionSeconds)), completed }),
  save: (id: number, saved: boolean) => (saved ? http.put<void>(`${ep(id)}/save`) : http.del<void>(`${ep(id)}/save`)),
  studio: (slug: string) => http.get<EpisodeSummary[]>(`/communities/${encodeURIComponent(slug)}/podcasts/studio`),
  create: (slug: string, body: { title: string; summary?: string; type: EpisodeType }) =>
    http.post<EpisodeDetail>(`/communities/${encodeURIComponent(slug)}/podcasts/episodes`, body),
  update: (id: number, body: EpisodePatch) => http.patch<EpisodeDetail>(ep(id), body),
  thumbnail: (id: number, file: File) => http.upload<EpisodeDetail>(`${ep(id)}/thumbnail`, file),
  transcriptImport: (id: number, format: TranscriptFormat, content: string) =>
    http.put<{ segments: number }>(`${ep(id)}/transcript`, { format, content }),
  submit: (id: number) => http.post<EpisodeDetail>(`${ep(id)}/submit`),
  publish: (id: number) => http.post<EpisodeDetail>(`${ep(id)}/publish`),
  unpublish: (id: number) => http.post<EpisodeDetail>(`${ep(id)}/unpublish`),
  remove: (id: number) => http.del<void>(ep(id)),
  draftScript: (id: number, topic: string, targetMinutes: number) =>
    http.post<EpisodeDetail>(`${ep(id)}/draft-script`, { topic, targetMinutes }),
  audioGeneration: (id: number) => http.get<AudioGeneration>(`${ep(id)}/audio-generation`),
  generateAudio: (id: number) => http.post<AudioJob>(`${ep(id)}/audio-generation`),
  cancelAudio: (id: number) => http.post<AudioJob>(`${ep(id)}/audio-generation/cancel`),
};

/**
 * Media upload with progress. Uses XHR (fetch has no upload progress) and the same
 * Bearer + single-flight refresh behaviour as the main client.
 */
export async function uploadMedia(
  id: number,
  file: File,
  durationSeconds: number,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<EpisodeDetail> {
  const attempt = () =>
    new Promise<{ status: number; body: string }>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${API_BASE}${ep(id)}/media`);
      xhr.withCredentials = true;
      xhr.setRequestHeader('Accept', 'application/json');
      const token = useAuthStore.getState().accessToken;
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
      xhr.onload = () => resolve({ status: xhr.status, body: xhr.responseText });
      xhr.onerror = () => reject(ApiError.network());
      xhr.onabort = () => reject(new DOMException('Upload cancelled', 'AbortError'));
      signal?.addEventListener('abort', () => xhr.abort(), { once: true });
      const fd = new FormData();
      fd.append('file', file);
      fd.append('durationSeconds', String(Math.round(durationSeconds)));
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
    throw new ApiError({
      status: res.status,
      code: (json.code as string) ?? 'HTTP_ERROR',
      message: (json.message as string) ?? (res.status === 413 ? 'This file is too large' : 'Upload failed'),
      fieldErrors: (json.fieldErrors as Record<string, string>) ?? {},
      traceId: json.traceId as string | undefined,
    });
  }
  return json as unknown as EpisodeDetail;
}

/** Best-effort progress save that survives page unload (fetch keepalive; Bearer auth rules out sendBeacon). */
export function saveProgressKeepalive(id: number, positionSeconds: number, completed?: boolean) {
  const token = useAuthStore.getState().accessToken;
  if (!token) return;
  try {
    void fetch(`${API_BASE}${ep(id)}/progress`, {
      method: 'PUT',
      keepalive: true,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ positionSeconds: Math.max(0, Math.round(positionSeconds)), completed }),
    }).catch(() => undefined);
  } catch {
    /* ignore */
  }
}

export const podcastKeys = {
  all: ['podcasts'] as const,
  capabilities: ['podcasts', 'capabilities'] as const,
  home: ['podcasts', 'home'] as const,
  list: (p: ListParams) => ['podcasts', 'list', p] as const,
  history: ['podcasts', 'history'] as const,
  episode: (id: number) => ['podcasts', 'episode', id] as const,
  transcript: (id: number, q: string) => ['podcasts', 'transcript', id, q] as const,
  studio: (slug: string) => ['podcasts', 'studio', slug] as const,
  audio: (id: number) => ['podcasts', 'audio', id] as const,
};

const cursorOpts = {
  initialPageParam: undefined as string | undefined,
  getNextPageParam: (last: { nextCursor: string | null }) => last.nextCursor ?? undefined,
};

export const useCapabilities = () =>
  useQuery({ queryKey: podcastKeys.capabilities, queryFn: podcastApi.capabilities, staleTime: 10 * 60_000 });

export const usePodcastHome = () => useQuery({ queryKey: podcastKeys.home, queryFn: podcastApi.home });

export const useEpisodeList = (p: ListParams, enabled = true) =>
  useInfiniteQuery({ queryKey: podcastKeys.list(p), queryFn: ({ pageParam }) => podcastApi.list(p, pageParam), enabled, ...cursorOpts });

export const useListeningHistory = (enabled: boolean) =>
  useInfiniteQuery({ queryKey: podcastKeys.history, queryFn: ({ pageParam }) => podcastApi.history(pageParam), enabled, ...cursorOpts });

/** Polls while an AI script draft is being generated. */
export const useEpisode = (id: number, opts: { poll?: boolean } = {}) =>
  useQuery({
    queryKey: podcastKeys.episode(id),
    queryFn: () => podcastApi.get(id),
    enabled: Number.isFinite(id),
    refetchInterval: (q) => (opts.poll && q.state.data?.scriptStatus === 'GENERATING' ? 4000 : false),
  });

export const useTranscript = (id: number, q: string, enabled: boolean) =>
  useQuery({ queryKey: podcastKeys.transcript(id, q), queryFn: () => podcastApi.transcript(id, q), enabled, placeholderData: (p) => p });

export const useStudio = (slug: string) =>
  useQuery({ queryKey: podcastKeys.studio(slug), queryFn: () => podcastApi.studio(slug), retry: false });

/** Episode mutations: write the fresh detail into the cache and refresh lists. */
export function useEpisodeMutation<V>(id: number, fn: (v: V) => Promise<EpisodeDetail | void>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (d) => {
      if (d) qc.setQueryData(podcastKeys.episode(id), d);
      else qc.invalidateQueries({ queryKey: podcastKeys.episode(id) });
      qc.invalidateQueries({ queryKey: ['podcasts', 'studio'] });
      qc.invalidateQueries({ queryKey: podcastKeys.home });
      qc.invalidateQueries({ queryKey: ['podcasts', 'list'] });
    },
  });
}

export function useSaveEpisode(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (saved: boolean) => podcastApi.save(id, saved),
    onSuccess: (_d, saved) => {
      qc.setQueryData<EpisodeDetail>(podcastKeys.episode(id), (old) => (old ? { ...old, saved } : old));
      qc.invalidateQueries({ queryKey: podcastKeys.home });
    },
  });
}

/** Audio generation status; polls every 3 s while a job is queued or generating. */
export const useAudioGeneration = (id: number, enabled: boolean) =>
  useQuery({
    queryKey: podcastKeys.audio(id),
    queryFn: () => podcastApi.audioGeneration(id),
    enabled,
    refetchInterval: (q) => (q.state.data?.job && ['QUEUED', 'GENERATING'].includes(q.state.data.job.status) ? 3000 : false),
  });

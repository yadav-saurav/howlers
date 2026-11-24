import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import type {
  ModerationRecord,
  ModeratorSettings,
  ModLeaderboard,
  ModPeers,
  PostExpertise,
  ReputationOverview,
  TopicLeaderboard,
  UserReputation,
} from '@/types/recognition';

const u = (username: string) => `/users/${encodeURIComponent(username)}`;
const c = (slug: string) => `/communities/${encodeURIComponent(slug)}/moderators`;

export const recognitionApi = {
  topicLeaders: (slug: string) => http.get<TopicLeaderboard>(`/topics/${encodeURIComponent(slug)}/leaders`, { limit: 50 }),
  overview: () => http.get<ReputationOverview>('/reputation/topics'),
  userReputation: (username: string) => http.get<UserReputation>(`${u(username)}/reputation`),
  postExpertise: (postId: number) => http.get<PostExpertise>(`/posts/${postId}/expertise`),
  modLeaderboard: (period?: string, community?: string) =>
    http.get<ModLeaderboard>('/moderators/leaderboard', { period, community: community || undefined }),
  modRecord: (username: string) => http.get<ModerationRecord>(`${u(username)}/moderation-record`),
  peers: (slug: string) => http.get<ModPeers>(`${c(slug)}/peers`),
  endorse: (slug: string, username: string, note?: string) =>
    http.post<void>(`${c(slug)}/${encodeURIComponent(username)}/endorsement`, { note: note || undefined }),
  withdraw: (slug: string, username: string) => http.del<void>(`${c(slug)}/${encodeURIComponent(username)}/endorsement`),
  settings: () => http.get<ModeratorSettings>('/me/moderator-settings'),
  updateSettings: (body: ModeratorSettings) => http.put<ModeratorSettings>('/me/moderator-settings', body),
};

export const recognitionKeys = {
  topicLeaders: (slug: string) => ['reputation', 'leaders', slug] as const,
  overview: ['reputation', 'overview'] as const,
  user: (username: string) => ['reputation', 'user', username] as const,
  post: (postId: number) => ['reputation', 'post', postId] as const,
  modLeaderboard: (period: string, community: string) => ['mod-recognition', 'leaderboard', period, community] as const,
  modRecord: (username: string) => ['mod-recognition', 'record', username] as const,
  peers: (slug: string) => ['mod-recognition', 'peers', slug] as const,
  settings: ['mod-recognition', 'settings'] as const,
};

const STALE = 60_000;

export const useTopicLeaders = (slug: string) =>
  useQuery({ queryKey: recognitionKeys.topicLeaders(slug), queryFn: () => recognitionApi.topicLeaders(slug), staleTime: STALE });
export const useReputationOverview = () =>
  useQuery({ queryKey: recognitionKeys.overview, queryFn: recognitionApi.overview, staleTime: STALE });
export const useUserReputation = (username: string) =>
  useQuery({ queryKey: recognitionKeys.user(username), queryFn: () => recognitionApi.userReputation(username), staleTime: STALE, retry: false });

/** One request per thread page; every comment's chip reads from this cached map. */
export const usePostExpertise = (postId: number) =>
  useQuery({
    queryKey: recognitionKeys.post(postId),
    queryFn: () => recognitionApi.postExpertise(postId),
    staleTime: 5 * 60_000,
    retry: false,
    enabled: Number.isFinite(postId) && postId > 0,
  });

export const useModLeaderboard = (period: string, community: string) =>
  useQuery({
    queryKey: recognitionKeys.modLeaderboard(period, community),
    queryFn: () => recognitionApi.modLeaderboard(period || undefined, community || undefined),
    staleTime: STALE,
    retry: false,
  });
export const useModRecord = (username: string) =>
  useQuery({ queryKey: recognitionKeys.modRecord(username), queryFn: () => recognitionApi.modRecord(username), staleTime: STALE, retry: false });
export const usePeers = (slug: string) =>
  useQuery({ queryKey: recognitionKeys.peers(slug), queryFn: () => recognitionApi.peers(slug), retry: false });
export const useModeratorSettings = () => useQuery({ queryKey: recognitionKeys.settings, queryFn: recognitionApi.settings });

export function useEndorse(slug: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ username, note, withdraw }: { username: string; note?: string; withdraw?: boolean }) =>
      withdraw ? recognitionApi.withdraw(slug, username) : recognitionApi.endorse(slug, username, note),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['mod-recognition'] }),
  });
}

export function useUpdateModeratorSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: recognitionApi.updateSettings,
    onSuccess: (data) => {
      qc.setQueryData(recognitionKeys.settings, data);
      void qc.invalidateQueries({ queryKey: ['mod-recognition'] });
    },
  });
}

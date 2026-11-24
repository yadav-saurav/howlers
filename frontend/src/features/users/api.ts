import { http } from '@/services/apiClient';
import type { CursorPage, Me, PostSummary, PublicProfile, UserComment, UserSettings } from '@/types/api';

export const usersApi = {
  me: () => http.get<Me>('/users/me'),
  updateMe: (body: { displayName?: string; bio?: string }) => http.patch<Me>('/users/me', body),
  updateSettings: (body: Partial<UserSettings>) => http.patch<Me>('/users/me/settings', body),
  uploadAvatar: (file: File) => http.upload<Me>('/users/me/avatar', file),
  uploadBanner: (file: File) => http.upload<Me>('/users/me/banner', file),
  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    http.post<void>('/users/me/password', body),
  deactivate: (password: string) => http.post<void>('/users/me/deactivate', { password }),
  deleteAccount: (password: string) => http.del<void>('/users/me', { password }),
  profile: (username: string) => http.get<PublicProfile>(`/users/${encodeURIComponent(username)}`),
  posts: (username: string, cursor?: string) =>
    http.get<CursorPage<PostSummary>>(`/users/${encodeURIComponent(username)}/posts`, { cursor }),
  comments: (username: string, cursor?: string) =>
    http.get<CursorPage<UserComment>>(`/users/${encodeURIComponent(username)}/comments`, { cursor }),
  follow: (username: string) => http.post<void>(`/users/${encodeURIComponent(username)}/follow`),
  unfollow: (username: string) => http.del<void>(`/users/${encodeURIComponent(username)}/follow`),
};

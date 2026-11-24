import { http } from '@/services/apiClient';
import type { AppNotification, CursorPage } from '@/types/api';

export const notificationsApi = {
  list: (cursor?: string, unreadOnly = false) =>
    http.get<CursorPage<AppNotification>>('/notifications', { cursor, unreadOnly }),
  unreadCount: () => http.get<{ count: number }>('/notifications/unread-count'),
  markRead: (id: number) => http.post<void>(`/notifications/${id}/read`),
  markAllRead: () => http.post<void>('/notifications/read-all'),
};

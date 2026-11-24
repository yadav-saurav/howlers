import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { qk } from '@/services/queryKeys';
import { useRealtimeStore } from '@/services/realtime';
import { useAuthStore } from '@/store/authStore';
import type { AppNotification, CursorPage } from '@/types/api';
import { notificationsApi } from './api';

export function useUnreadCount() {
  const authed = useAuthStore((s) => s.status === 'authenticated');
  // NOTIFICATION frames update the count instantly; poll slowly only while the socket is down.
  const live = useRealtimeStore((s) => s.status === 'connected');
  return useQuery({
    queryKey: qk.unreadCount,
    queryFn: notificationsApi.unreadCount,
    enabled: authed,
    refetchInterval: live ? false : 60_000,
    refetchIntervalInBackground: false,
  });
}

export function useNotifications(unreadOnly: boolean) {
  return useInfiniteQuery({
    queryKey: [...qk.notifications, 'list', unreadOnly],
    queryFn: ({ pageParam }) => notificationsApi.list(pageParam, unreadOnly),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

type NotifPages = InfiniteData<CursorPage<AppNotification>>;

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => notificationsApi.markRead(id),
    onMutate: (id) => {
      for (const [key, data] of qc.getQueriesData<NotifPages>({ queryKey: [...qk.notifications, 'list'] })) {
        if (!data) continue;
        qc.setQueryData<NotifPages>(key, {
          ...data,
          pages: data.pages.map((p) => ({ ...p, items: p.items.map((n) => (n.id === id ? { ...n, read: true } : n)) })),
        });
      }
    },
    onSettled: () => qc.invalidateQueries({ queryKey: qk.unreadCount }),
  });
}

export function useMarkAllRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: notificationsApi.markAllRead,
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.notifications }),
  });
}

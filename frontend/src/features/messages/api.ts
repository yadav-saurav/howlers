import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import { useRealtimeStore } from '@/services/realtime';
import { useAuthStore } from '@/store/authStore';
import type { ConversationSummary, DmMedia, DmMessage, MessagePage, RoomDetail } from '@/types/messages';
import { applyRead, pagesAppend, pagesDelete, type MessagePages } from './helpers';
import { applyRoomRead } from './rooms';

export const PAGE_SIZE = 30;

export const dmApi = {
  conversations: (q?: string) => http.get<ConversationSummary[]>('/me/conversations', { q: q || undefined }),
  open: (username: string) => http.post<ConversationSummary>('/me/conversations', { username }),
  conversation: (id: number) => http.get<ConversationSummary>(`/me/conversations/${id}`),
  messages: (id: number, before?: number) =>
    http.get<MessagePage>(`/me/conversations/${id}/messages`, { before, limit: PAGE_SIZE }),
  send: (id: number, body: string, mediaIds: number[]) =>
    http.post<DmMessage>(`/me/conversations/${id}/messages`, { body: body || undefined, mediaIds: mediaIds.length ? mediaIds : undefined }),
  read: (id: number, messageId: number) => http.post<void>(`/me/conversations/${id}/read`, { messageId }),
  remove: (messageId: number) => http.del<void>(`/me/messages/${messageId}`),
  unreadCount: () => http.get<{ count: number }>('/me/messages/unread-count'),
  /** DM images go to private storage and come back with a participant-only signed URL (D-024). */
  uploadMedia: (file: File) => http.upload<DmMedia>('/me/dm-media', file),
};

/** Pack rooms (D-043). Messages, receipts and typing use the conversation endpoints above. */
export const roomApi = {
  create: (body: { name: string; iconEmoji?: string | null; usernames: string[] }) => http.post<ConversationSummary>('/me/rooms', body),
  get: (id: number) => http.get<RoomDetail>(`/me/rooms/${id}`),
  update: (id: number, body: { name?: string; iconEmoji?: string }) => http.patch<RoomDetail>(`/me/rooms/${id}`, body),
  addMembers: (id: number, usernames: string[]) => http.post<RoomDetail>(`/me/rooms/${id}/members`, { usernames }),
  removeMember: (id: number, userId: number) => http.del<RoomDetail>(`/me/rooms/${id}/members/${userId}`),
  setRole: (id: number, userId: number, role: 'ADMIN' | 'MEMBER') => http.put<RoomDetail>(`/me/rooms/${id}/members/${userId}/role`, { role }),
  transfer: (id: number, userId: number) => http.post<RoomDetail>(`/me/rooms/${id}/transfer`, { userId }),
  leave: (id: number) => http.post<void>(`/me/rooms/${id}/leave`),
  remove: (id: number) => http.del<void>(`/me/rooms/${id}`),
};

export const dmKeys = {
  all: ['dm'] as const,
  lists: ['dm', 'list'] as const,
  list: (q: string) => ['dm', 'list', q] as const,
  conversation: (id: number) => ['dm', 'conversation', id] as const,
  messages: (id: number) => ['dm', 'messages', id] as const,
  room: (id: number) => ['dm', 'room', id] as const,
  unread: ['dm', 'unread'] as const,
};

export function useRoom(id: number, enabled: boolean) {
  return useQuery({ queryKey: dmKeys.room(id), queryFn: () => roomApi.get(id), enabled: enabled && Number.isFinite(id), retry: false });
}

/** Room mutations return the fresh detail; the conversation summary and inbox refresh around it. */
export function useRoomMutation<V>(id: number, fn: (v: V) => Promise<RoomDetail | void>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (d) => {
      if (d) qc.setQueryData(dmKeys.room(id), d);
      void qc.invalidateQueries({ queryKey: dmKeys.conversation(id) });
      void qc.invalidateQueries({ queryKey: dmKeys.lists });
      void qc.invalidateQueries({ queryKey: dmKeys.messages(id) });
    },
  });
}

/** Leaving or deleting: drop everything cached about the room. */
export function forgetRoom(qc: QC, id: number) {
  qc.removeQueries({ queryKey: dmKeys.room(id) });
  qc.removeQueries({ queryKey: dmKeys.messages(id) });
  qc.removeQueries({ queryKey: dmKeys.conversation(id) });
  void qc.invalidateQueries({ queryKey: dmKeys.lists });
  void qc.invalidateQueries({ queryKey: dmKeys.unread });
}

export function useCreateRoom() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: roomApi.create,
    onSuccess: (s) => {
      qc.setQueryData(dmKeys.conversation(s.id), s);
      void qc.invalidateQueries({ queryKey: dmKeys.lists });
    },
  });
}

const useLive = () => useRealtimeStore((s) => s.status === 'connected');

export function useConversations(q: string) {
  const live = useLive();
  return useQuery({
    queryKey: dmKeys.list(q),
    queryFn: () => dmApi.conversations(q),
    refetchInterval: live ? false : 60_000,
    placeholderData: (prev) => prev,
  });
}

export function useConversation(id: number) {
  return useQuery({ queryKey: dmKeys.conversation(id), queryFn: () => dmApi.conversation(id), enabled: Number.isFinite(id), retry: false });
}

export function useMessages(id: number) {
  const live = useLive();
  return useInfiniteQuery({
    queryKey: dmKeys.messages(id),
    queryFn: ({ pageParam }) => dmApi.messages(id, pageParam),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.hasMore && last.items.length ? last.items[0].id : undefined),
    enabled: Number.isFinite(id),
    // Without a socket, poll the newest page so the thread still updates.
    refetchInterval: live ? false : 15_000,
  });
}

export function useDmUnreadCount() {
  const authed = useAuthStore((s) => s.status === 'authenticated');
  const live = useLive();
  return useQuery({
    queryKey: dmKeys.unread,
    queryFn: dmApi.unreadCount,
    enabled: authed,
    refetchInterval: live ? false : 60_000,
    refetchIntervalInBackground: false,
  });
}

/* ------------------------------------------------------------------ cache writers shared by REST and WS */

type QC = ReturnType<typeof useQueryClient>;

export function cacheMessage(qc: QC, msg: DmMessage) {
  qc.setQueryData<MessagePages>(dmKeys.messages(msg.conversationId), (d) => pagesAppend(d, msg));
}

export function cacheDelete(qc: QC, conversationId: number, messageId: number) {
  qc.setQueryData<MessagePages>(dmKeys.messages(conversationId), (d) => pagesDelete(d, messageId));
}

export function cacheRead(qc: QC, conversationId: number, userId: number, messageId: number, meId: number | undefined) {
  const fn = (s: ConversationSummary | undefined) => (s ? applyRead(s, userId, messageId, meId) : s);
  qc.setQueryData<ConversationSummary>(dmKeys.conversation(conversationId), fn);
  qc.setQueriesData<ConversationSummary[]>({ queryKey: dmKeys.lists }, (list) =>
    list?.map((s) => (s.id === conversationId ? fn(s)! : s)),
  );
  // Pack rooms keep one receipt per member for "Seen by N".
  qc.setQueryData<RoomDetail>(dmKeys.room(conversationId), (d) => (d ? applyRoomRead(d, userId, messageId) : d));
}

export function cachePresence(qc: QC, userId: number, online: boolean) {
  const fn = (s: ConversationSummary) => (s.other?.id === userId && s.online !== online ? { ...s, online } : s);
  qc.setQueriesData<ConversationSummary[]>({ queryKey: dmKeys.lists }, (list) => list?.map(fn));
  qc.setQueriesData<ConversationSummary>({ queryKey: ['dm', 'conversation'] }, (s) => (s ? fn(s) : s));
  qc.setQueriesData<RoomDetail>({ queryKey: ['dm', 'room'] }, (d) =>
    d && d.members.some((m) => m.user.id === userId && m.online !== online)
      ? { ...d, members: d.members.map((m) => (m.user.id === userId ? { ...m, online } : m)) }
      : d,
  );
  // A room's "someone is online" dot is computed on the server; refresh the inbox for it.
  void qc.invalidateQueries({ queryKey: dmKeys.lists, predicate: (q) => (q.state.data as ConversationSummary[] | undefined)?.some((s) => s.kind === 'ROOM') ?? false });
}

/* ------------------------------------------------------------------ mutations */

export function useOpenConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: dmApi.open,
    onSuccess: (s) => {
      qc.setQueryData(dmKeys.conversation(s.id), s);
      void qc.invalidateQueries({ queryKey: dmKeys.lists });
    },
  });
}

export function useMarkRead(id: number) {
  const qc = useQueryClient();
  const meId = useAuthStore((s) => s.user?.id);
  return useMutation({
    mutationFn: (messageId: number) => dmApi.read(id, messageId),
    onSuccess: (_, messageId) => {
      if (meId != null) cacheRead(qc, id, meId, messageId, meId);
      void qc.invalidateQueries({ queryKey: dmKeys.unread });
    },
  });
}

export function useDeleteMessage(conversationId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: dmApi.remove,
    onSuccess: (_, messageId) => {
      cacheDelete(qc, conversationId, messageId);
      void qc.invalidateQueries({ queryKey: dmKeys.lists });
    },
  });
}

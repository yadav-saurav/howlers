import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, type ReactNode } from 'react';
import { cacheDelete, cacheMessage, cachePresence, cacheRead, dmKeys, forgetRoom } from '@/features/messages/api';
import { refreshReactions } from '@/features/reactions/api';
import { qk } from '@/services/queryKeys';
import { publish, startRealtime, stopRealtime, subscribeTopic, useRealtimeStore, type RealtimeStatus } from '@/services/realtime';
import { useAuthStore } from '@/store/authStore';
import type { RealtimeEvent } from '@/types/messages';
import { useTypingStore } from './typingStore';

/**
 * Opens the STOMP connection while signed in and turns personal events into React Query cache updates.
 * Reconnects when the signed-in user changes and disconnects on logout. A token refresh for the same user keeps the
 * live socket (it was authenticated at CONNECT); the next reconnect reads the fresh token.
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const userId = useAuthStore((s) => (s.status === 'authenticated' ? s.user?.id ?? null : null));
  const qcRef = useRef(qc);
  qcRef.current = qc;

  useEffect(() => {
    if (userId == null) {
      stopRealtime();
      return;
    }
    startRealtime((raw) => handleEvent(qcRef.current, raw as RealtimeEvent, userId));
    return () => stopRealtime();
  }, [userId]);

  return <>{children}</>;
}

function handleEvent(qc: ReturnType<typeof useQueryClient>, ev: RealtimeEvent | null, meId: number) {
  if (!ev || typeof ev !== 'object' || !('type' in ev)) return;
  switch (ev.type) {
    case 'MESSAGE': {
      const m = ev.payload;
      cacheMessage(qc, m);
      useTypingStore.getState().clear(m.conversationId, m.senderId);
      void qc.invalidateQueries({ queryKey: dmKeys.lists });
      if (m.senderId !== meId) void qc.invalidateQueries({ queryKey: dmKeys.conversation(m.conversationId) });
      break;
    }
    case 'MESSAGE_DELETED':
      cacheDelete(qc, ev.payload.conversationId, ev.payload.messageId);
      void qc.invalidateQueries({ queryKey: dmKeys.lists });
      break;
    case 'READ':
      cacheRead(qc, ev.payload.conversationId, ev.payload.userId, ev.payload.messageId, meId);
      if (ev.payload.userId === meId) void qc.invalidateQueries({ queryKey: dmKeys.unread });
      break;
    case 'TYPING':
      if (ev.payload.userId !== meId) useTypingStore.getState().mark(ev.payload.conversationId, ev.payload.userId);
      break;
    case 'PRESENCE':
      cachePresence(qc, ev.payload.userId, ev.payload.online);
      break;
    case 'NOTIFICATION':
      qc.setQueryData(qk.unreadCount, { count: ev.payload.unreadCount });
      void qc.invalidateQueries({ queryKey: qk.notifications, predicate: (q) => q.queryKey[1] !== 'unread' });
      break;
    case 'UNREAD_MESSAGES':
      qc.setQueryData(dmKeys.unread, { count: ev.payload.count });
      break;
    case 'ROOM_UPDATED':
      void qc.invalidateQueries({ queryKey: dmKeys.room(ev.payload.conversationId) });
      void qc.invalidateQueries({ queryKey: dmKeys.conversation(ev.payload.conversationId) });
      void qc.invalidateQueries({ queryKey: dmKeys.lists });
      break;
    case 'ROOM_REMOVED':
      forgetRoom(qc, ev.payload.conversationId);
      break;
    case 'REACTIONS':
      refreshReactions(qc, 'MESSAGE', ev.payload.targetId);
      break;
  }
}

export function useRealtimeStatus(): RealtimeStatus {
  return useRealtimeStore((s) => s.status);
}

/**
 * Subscribes to a topic while mounted and `destination` is non-null. The callback may change between renders
 * without resubscribing.
 */
export function useTopic<T = unknown>(destination: string | null, onMessage: (body: T) => void): void {
  const cb = useRef(onMessage);
  cb.current = onMessage;
  useEffect(() => {
    if (!destination) return;
    return subscribeTopic(destination, (b) => cb.current(b as T));
  }, [destination]);
}

export { publish as publishRealtime };

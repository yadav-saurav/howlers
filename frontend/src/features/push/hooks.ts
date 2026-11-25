import { useEffect } from 'react';
import { useAuthStore } from '@/store/authStore';
import { currentSupport, disablePush, rememberedDevice } from './pushClient';

/**
 * Push notifications are switched off in this version. A browser that turned them on earlier is unsubscribed once,
 * on the next visit, so nobody keeps receiving pushes for a feature that is no longer in the app.
 */
export function useRetirePush() {
  const userId = useAuthStore((s) => s.user?.id);
  useEffect(() => {
    if (userId == null || !currentSupport().ok || rememberedDevice(userId) == null) return;
    void disablePush(userId, rememberedDevice(userId)).catch(() => undefined);
  }, [userId]);
}

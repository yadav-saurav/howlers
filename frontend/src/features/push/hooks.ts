import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useAuthStore } from '@/store/authStore';
import type { PushCategory } from '@/types/push';
import { pushApi } from './api';
import { currentSupport, syncPush } from './pushClient';

export const pushKeys = {
  config: ['push', 'config'] as const,
  devices: ['push', 'devices'] as const,
};

export function usePushConfig(enabled = true) {
  return useQuery({ queryKey: pushKeys.config, queryFn: pushApi.config, staleTime: 10 * 60_000, enabled });
}

export function usePushDevices(enabled = true) {
  return useQuery({ queryKey: pushKeys.devices, queryFn: pushApi.devices, enabled });
}

export function useUpdateDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, categories }: { id: number; categories: PushCategory[] }) => pushApi.update(id, { categories }),
    onSuccess: () => qc.invalidateQueries({ queryKey: pushKeys.devices }),
  });
}

export function useRemoveDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: pushApi.remove,
    onSuccess: () => qc.invalidateQueries({ queryKey: pushKeys.devices }),
  });
}

export function useTestPush() {
  return useMutation({ mutationFn: pushApi.test });
}

/** Keeps this browser's subscription current for a user who enabled push here before. Runs once per sign-in. */
export function usePushSync() {
  const userId = useAuthStore((s) => s.user?.id);
  const config = usePushConfig(userId != null && currentSupport().ok);
  const publicKey = config.data?.available ? config.data.publicKey : null;
  useEffect(() => {
    if (userId == null || !publicKey || !currentSupport().ok) return;
    void syncPush(userId, publicKey).catch(() => undefined);
  }, [userId, publicKey]);
}

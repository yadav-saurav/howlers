import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { releasePushOnSignOut } from '@/features/push/pushClient';
import { qk } from '@/services/queryKeys';
import { useAuthStore } from '@/store/authStore';
import type { AuthResponse } from '@/types/api';
import { usernameSchema } from '@/utils/validation';
import { isMfaChallenge } from '@/features/accountSecurity/logic';
import { authApi } from './api';

export function useAuth() {
  const user = useAuthStore((s) => s.user);
  const status = useAuthStore((s) => s.status);
  return { user, status, isAuthed: status === 'authenticated' && !!user };
}

function useSessionSetter() {
  const qc = useQueryClient();
  return (res: AuthResponse) => {
    useAuthStore.getState().setSession(res.accessToken, res.user);
    qc.invalidateQueries();
  };
}

export function useLogin() {
  const setSession = useSessionSetter();
  // With 2FA on, the first step returns a challenge, not a session; the form then asks for the code.
  return useMutation({ mutationFn: authApi.login, onSuccess: (r) => (isMfaChallenge(r) ? undefined : setSession(r)) });
}

export function useRegister() {
  const setSession = useSessionSetter();
  return useMutation({ mutationFn: authApi.register, onSuccess: setSession });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      // This browser stops receiving the account's pushes before the session ends (D-040).
      await releasePushOnSignOut(useAuthStore.getState().user?.id);
      return authApi.logout();
    },
    onSettled: () => {
      useAuthStore.getState().clear();
      qc.clear();
    },
  });
}

export function useSessions() {
  return useQuery({ queryKey: qk.sessions, queryFn: authApi.sessions });
}

export function useRevokeSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: authApi.revokeSession,
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.sessions }),
  });
}

export type Availability = 'idle' | 'invalid' | 'checking' | 'available' | 'taken' | 'error';

/** Debounced username availability check against /auth/username-available. */
export function useUsernameAvailability(username: string, delay = 400): { state: Availability; reason: string | null } {
  const [state, setState] = useState<Availability>('idle');
  const [reason, setReason] = useState<string | null>(null);

  useEffect(() => {
    if (!username) {
      setState('idle');
      return;
    }
    if (!usernameSchema.safeParse(username).success) {
      setState('invalid');
      return;
    }
    setState('checking');
    const ctrl = new AbortController();
    const t = window.setTimeout(() => {
      authApi
        .usernameAvailable(username, ctrl.signal)
        .then((r) => {
          setState(r.available ? 'available' : 'taken');
          setReason(r.reason);
        })
        .catch((e: unknown) => {
          if (e instanceof DOMException && e.name === 'AbortError') return;
          setState('error');
        });
    }, delay);
    return () => {
      ctrl.abort();
      window.clearTimeout(t);
    };
  }, [username, delay]);

  return { state, reason };
}

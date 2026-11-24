import { create } from 'zustand';
import type { Me } from '@/types/api';

export type AuthStatus = 'booting' | 'authenticated' | 'anonymous';

interface AuthState {
  /** Access token lives only in memory; the refresh token is an HttpOnly cookie. */
  accessToken: string | null;
  user: Me | null;
  status: AuthStatus;
  setSession: (token: string, user: Me) => void;
  setUser: (user: Me) => void;
  clear: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  status: 'booting',
  setSession: (accessToken, user) => set({ accessToken, user, status: 'authenticated' }),
  setUser: (user) => set({ user }),
  clear: () => set({ accessToken: null, user: null, status: 'anonymous' }),
}));

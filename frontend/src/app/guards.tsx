import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { PageSpinner } from '@/components/ui/Spinner';
import { useAuthStore } from '@/store/authStore';

/** Redirects anonymous visitors to /login?next=<current path>. Waits for session boot first. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const status = useAuthStore((s) => s.status);
  const location = useLocation();
  if (status === 'booting') return <PageSpinner />;
  if (status !== 'authenticated') {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  return <>{children}</>;
}

/** Keeps signed-in users away from login/register; honours ?next=. */
export function RedirectIfAuthed({ children }: { children: ReactNode }) {
  const status = useAuthStore((s) => s.status);
  const user = useAuthStore((s) => s.user);
  const location = useLocation();
  if (status === 'booting') return <PageSpinner />;
  if (status === 'authenticated' && user) {
    const next = new URLSearchParams(location.search).get('next');
    const safe = next && next.startsWith('/') && !next.startsWith('//') ? next : '/home';
    return <Navigate to={user.onboardingCompleted ? safe : '/onboarding'} replace />;
  }
  return <>{children}</>;
}

export function safeNext(search: string, fallback = '/home'): string {
  const next = new URLSearchParams(search).get('next');
  return next && next.startsWith('/') && !next.startsWith('//') ? next : fallback;
}

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

/**
 * Everything inside the app is for members. Anyone signed out who opens an app link (a shared post, a community, an
 * old bookmark, or the app right after logging out) lands on the landing page instead. The link they opened is kept
 * as ?next= so signing up or logging in takes them straight to it.
 */
export function MembersOnly({ children }: { children: ReactNode }) {
  const status = useAuthStore((s) => s.status);
  const location = useLocation();
  if (status === 'booting') return <PageSpinner />;
  if (status !== 'authenticated') {
    const here = location.pathname + location.search;
    return <Navigate to={here === '/home' || here === '/' ? '/' : `/?next=${encodeURIComponent(here)}`} replace />;
  }
  return <>{children}</>;
}

/** Appends the visitor's pending ?next= (from MembersOnly) to an auth link, so they return to what was shared. */
export function withNext(path: string, search: string): string {
  const next = new URLSearchParams(search).get('next');
  return next && next.startsWith('/') && !next.startsWith('//') ? `${path}?next=${encodeURIComponent(next)}` : path;
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

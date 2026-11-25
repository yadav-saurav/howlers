import { lazy, Suspense, type ComponentType, type ReactNode } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { PageSpinner } from '@/components/ui/Spinner';
import { AppLayout } from '@/layouts/AppLayout';
import { PublicLayout } from '@/layouts/PublicLayout';
import { MembersOnly, RedirectIfAuthed, RequireAuth } from './guards';
import { RouteError } from '@/routes/RouteError';

const page = (load: () => Promise<{ default: ComponentType }>) => {
  const C = lazy(load);
  return (
    <Suspense fallback={<PageSpinner />}>
      <C />
    </Suspense>
  );
};
const authed = (el: ReactNode) => <RequireAuth>{el}</RequireAuth>;
const guest = (el: ReactNode) => <RedirectIfAuthed>{el}</RedirectIfAuthed>;
/** Library, Community AI, Podcasts, Moderator funding and Files are switched off: old links land on the home feed. */
const retired = <Navigate to="/home" replace />;

export const router = createBrowserRouter([
  {
    element: <PublicLayout />,
    errorElement: <RouteError />,
    children: [
      { path: '/', element: page(() => import('@/routes/landing/LandingPage')) },
      { path: '/login', element: guest(page(() => import('@/routes/auth/LoginPage'))) },
      { path: '/register', element: guest(page(() => import('@/routes/auth/RegisterPage'))) },
      { path: '/forgot-password', element: page(() => import('@/routes/auth/ForgotPasswordPage')) },
      { path: '/reset-password', element: page(() => import('@/routes/auth/ResetPasswordPage')) },
      { path: '/verify-email', element: page(() => import('@/routes/auth/VerifyEmailPage')) },
      { path: '/about', element: page(() => import('@/routes/static/AboutPage')) },
      { path: '/terms', element: page(() => import('@/routes/static/TermsPage')) },
      { path: '/privacy', element: page(() => import('@/routes/static/PrivacyPage')) },
      { path: '/developers', element: page(() => import('@/routes/developers/ApiDocsPage')) },
      { path: '/docs', element: <Navigate to="/developers" replace /> },
      { path: '/onboarding', element: authed(page(() => import('@/routes/onboarding/OnboardingPage'))) },
      { path: '/s/:token', element: <Navigate to="/" replace /> },
      { path: '/security/e2ee', element: <Navigate to="/" replace /> },
    ],
  },
  {
    element: <MembersOnly><AppLayout /></MembersOnly>,
    errorElement: <RouteError />,
    children: [
      { path: '/home', element: page(() => import('@/routes/feed/HomePage')) },
      { path: '/popular', element: <Navigate to="/home?tab=popular" replace /> },
      { path: '/explore', element: page(() => import('@/routes/explore/ExplorePage')) },
      { path: '/communities', element: page(() => import('@/routes/communities/CommunitiesPage')) },
      { path: '/communities/new', element: authed(page(() => import('@/routes/communities/CreateCommunityPage'))) },
      { path: '/c/:slug', element: page(() => import('@/routes/communities/CommunityPage')) },
      { path: '/c/:slug/settings', element: authed(page(() => import('@/routes/communities/CommunitySettingsPage'))) },
      { path: '/c/:slug/mod', element: authed(page(() => import('@/routes/communities/ModPage'))) },
      { path: '/mod-actions/:id', element: authed(page(() => import('@/routes/moderation/ModActionPage'))) },
      { path: '/c/:slug/submit', element: authed(page(() => import('@/routes/posts/SubmitPage'))) },
      { path: '/c/:slug/p/:id', element: page(() => import('@/routes/posts/PostPage')) },
      { path: '/p/:id', element: page(() => import('@/routes/posts/PostPage')) },
      { path: '/submit', element: authed(page(() => import('@/routes/posts/SubmitPage'))) },
      { path: '/search', element: page(() => import('@/routes/search/SearchPage')) },
      { path: '/u/:username', element: page(() => import('@/routes/profile/ProfilePage')) },
      { path: '/settings', element: authed(page(() => import('@/routes/settings/SettingsPage'))) },
      { path: '/saved', element: authed(page(() => import('@/routes/saved/SavedPage'))) },
      { path: '/podcasts', element: retired },
      { path: '/podcasts/:id', element: retired },
      { path: '/c/:slug/podcasts/studio', element: retired },
      { path: '/c/:slug/podcasts/studio/:id', element: retired },
      { path: '/ai', element: retired },
      { path: '/library', element: retired },
      { path: '/collections/shared/:token', element: retired },
      { path: '/messages', element: authed(page(() => import('@/routes/messages/MessagesPage'))) },
      { path: '/messages/:id', element: authed(page(() => import('@/routes/messages/MessagesPage'))) },
      { path: '/notifications', element: authed(page(() => import('@/routes/notifications/NotificationsPage'))) },
      { path: '/files', element: retired },
      { path: '/admin', element: authed(page(() => import('@/routes/admin/AdminPage'))) },
      { path: '/soon/messages', element: <Navigate to="/messages" replace /> },
      { path: '/funding', element: retired },
      { path: '/funding/pools/:id', element: retired },
      { path: '/funding/payouts', element: retired },
      { path: '/soon/funding', element: retired },
      { path: '/leaderboard', element: page(() => import('@/routes/leaderboard/LeaderboardPage')) },
      { path: '/topics/:slug/leaders', element: page(() => import('@/routes/leaderboard/TopicLeadersPage')) },
      { path: '/soon/leaderboard', element: <Navigate to="/leaderboard" replace /> },
      { path: '/soon/:feature', element: page(() => import('@/routes/static/ComingSoonPage')) },
      { path: '*', element: page(() => import('@/routes/static/NotFoundPage')) },
    ],
  },
], {
  future: { v7_relativeSplatPath: true, v7_fetcherPersist: true, v7_normalizeFormMethod: true, v7_partialHydration: true, v7_skipActionErrorRevalidation: true },
});

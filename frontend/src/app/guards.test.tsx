import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/store/authStore';
import type { Me } from '@/types/api';
import { RedirectIfAuthed, RequireAuth, safeNext } from './guards';

const me = (onboardingCompleted = true): Me => ({
  id: 1, username: 'ada', email: 'a@x.io', displayName: null, avatarUrl: null, bannerUrl: null, bio: null, emailVerified: true,
  onboardingCompleted, role: 'USER', postKarma: 0, commentKarma: 0, createdAt: '2026-01-01T00:00:00Z',
  settings: { theme: 'dark', showNsfw: false, profilePublic: true, showCommunities: true, emailNotifications: false },
});

function Where() {
  const l = useLocation();
  return <div data-testid="where">{l.pathname + l.search}</div>;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/saved" element={<RequireAuth><div>secret</div></RequireAuth>} />
        <Route path="/login" element={<RedirectIfAuthed><div>login form</div></RedirectIfAuthed>} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('route guards', () => {
  beforeEach(() => useAuthStore.setState({ accessToken: null, user: null, status: 'anonymous' }));

  it('redirects anonymous users to /login with next', () => {
    render(
      <MemoryRouter initialEntries={['/saved?x=1']}>
        <Routes>
          <Route path="/saved" element={<RequireAuth><div>secret</div></RequireAuth>} />
          <Route path="/login" element={<Where />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByTestId('where').textContent).toBe('/login?next=%2Fsaved%3Fx%3D1');
  });

  it('shows a spinner (not a redirect) while the session is booting', () => {
    useAuthStore.setState({ status: 'booting' });
    renderAt('/saved');
    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.queryByText('secret')).not.toBeInTheDocument();
  });

  it('renders protected content for authenticated users', () => {
    useAuthStore.setState({ accessToken: 't', user: me(), status: 'authenticated' });
    renderAt('/saved');
    expect(screen.getByText('secret')).toBeInTheDocument();
  });

  it('sends signed-in users away from login to a safe next path', () => {
    useAuthStore.setState({ accessToken: 't', user: me(), status: 'authenticated' });
    renderAt('/login?next=/c/books');
    expect(screen.getByTestId('where').textContent).toBe('/c/books');
  });

  it('sends users who have not finished onboarding to /onboarding', () => {
    useAuthStore.setState({ accessToken: 't', user: me(false), status: 'authenticated' });
    renderAt('/login');
    expect(screen.getByTestId('where').textContent).toBe('/onboarding');
  });

  it('safeNext rejects protocol-relative and absolute URLs', () => {
    expect(safeNext('?next=//evil.example')).toBe('/home');
    expect(safeNext('?next=https://evil.example')).toBe('/home');
    expect(safeNext('?next=/u/ada')).toBe('/u/ada');
  });
});

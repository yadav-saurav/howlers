import { Link, Outlet, ScrollRestoration, useLocation } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { HOWLERS, HowlersMark } from '@/design-system/HowlersMark';
import { useAuth } from '@/features/auth/hooks';
import { cn } from '@/utils/format';
import { ThemeToggle } from './ThemeToggle';

/**
 * Public header. On the landing page (`landing`) it sits over the always-dark night scene: translucent, with
 * in-page links, and without the theme toggle (the landing doesn't follow the theme, so the toggle would do
 * nothing visible there).
 */
export function PublicHeader({ landing = false }: { landing?: boolean }) {
  const { isAuthed } = useAuth();
  const link = 'hidden rounded-full px-3 py-2 text-sm font-medium text-muted transition-colors hover:text-text';
  return (
    <header
      className={cn(
        'sticky top-0 z-30 border-b backdrop-blur',
        landing
          ? 'border-white/10 bg-[#0b0922]/75 supports-[backdrop-filter]:bg-[#0b0922]/60'
          : 'border-border/70 bg-bg/90 supports-[backdrop-filter]:bg-bg/75',
      )}
    >
      <div className={cn('mx-auto flex h-14 items-center justify-between gap-3 px-4 md:px-6', landing ? 'max-w-[1320px]' : 'max-w-6xl')}>
        <Link to="/" aria-label={`${HOWLERS.name} home`} className="rounded-md">
          <HowlersMark withName />
        </Link>
        {landing && (
          <nav aria-label="On this page" className="hidden items-center gap-1 lg:flex">
            <a href="#features" className={cn(link, 'lg:block')}>Features</a>
            <a href="#safety" className={cn(link, 'lg:block')}>Safety</a>
            <a href="#faq" className={cn(link, 'lg:block')}>FAQ</a>
          </nav>
        )}
        <nav aria-label="Primary" className="flex items-center gap-1.5">
          <Link to="/explore" className={cn(link, 'sm:block')}>
            Explore
          </Link>
          <Link to="/communities" className={cn(link, landing ? 'md:block' : 'sm:block')}>
            Communities
          </Link>
          {!landing && <ThemeToggle />}
          {isAuthed ? (
            <Link to="/home" className={buttonClasses('primary', 'sm', landing ? 'bg-white text-[#15113a] hover:bg-white/90' : undefined)}>
              Open {HOWLERS.name}
            </Link>
          ) : (
            <>
              <Link to="/login" className={buttonClasses('ghost', 'sm')}>
                Log in
              </Link>
              <Link
                to="/register"
                className={buttonClasses('primary', 'sm', landing ? 'bg-white px-4 text-[#15113a] hover:bg-white/90' : undefined)}
              >
                Join
              </Link>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}

export function PublicFooter({ landing = false }: { landing?: boolean }) {
  const col = 'flex flex-col gap-2.5';
  const a = 'w-max rounded-sm text-muted transition-colors hover:text-text';
  const head = 'mb-1 text-xs font-semibold uppercase tracking-wider text-faint';
  return (
    <footer className={cn('border-t', landing ? 'border-white/10 bg-[#07061a]' : 'border-border')}>
      <div className={cn('mx-auto grid gap-10 px-4 py-12 text-sm md:px-6 lg:grid-cols-[1.3fr_2fr]', landing ? 'max-w-[1200px]' : 'max-w-6xl')}>
        <div className="flex max-w-xs items-center gap-4">
          <HowlersMark size={64} />
          <div>
            <span className="font-display text-[17px] font-extrabold uppercase tracking-tight text-text">{HOWLERS.name}</span>
            <p className="mt-1.5 text-muted">{HOWLERS.tagline}.</p>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-8 sm:grid-cols-4">
          <nav aria-label="Product" className={col}>
            <span className={head}>Product</span>
            <Link to="/explore" className={a}>Explore</Link>
            <Link to="/communities" className={a}>Communities</Link>
            <Link to="/podcasts" className={a}>Podcasts</Link>
            <Link to="/leaderboard" className={a}>Leaderboard</Link>
          </nav>
          <nav aria-label="Howlers" className={col}>
            <span className={head}>Howlers</span>
            <Link to="/about" className={a}>About</Link>
            <Link to="/register" className={a}>Create account</Link>
            <Link to="/login" className={a}>Sign in</Link>
          </nav>
          <nav aria-label="Trust" className={col}>
            <span className={head}>Trust</span>
            <Link to="/security/e2ee" className={a}>File encryption</Link>
            <Link to="/terms" className={a}>Terms</Link>
            <Link to="/privacy" className={a}>Privacy</Link>
          </nav>
          <nav aria-label="Developers" className={col}>
            <span className={head}>Developers</span>
            <a href="/swagger-ui.html" className={a}>API docs</a>
          </nav>
        </div>
      </div>
      <div className={cn('border-t py-5 text-center text-xs text-muted', landing ? 'border-white/10' : 'border-border')}>
        © {new Date().getFullYear()} {HOWLERS.name}. Made for packs that like each other.
      </div>
    </footer>
  );
}

export function PublicLayout() {
  const landing = useLocation().pathname === '/';
  return (
    // The landing page is always dark: the `dark` class scopes the dark tokens to this subtree only.
    <div className={cn('flex min-h-screen flex-col', landing && 'dark bg-[#0d0b26] text-text')}>
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-3 focus:z-50 focus:rounded-md focus:bg-surface focus:px-3 focus:py-2">
        Skip to content
      </a>
      <PublicHeader landing={landing} />
      <main id="main" tabIndex={-1} className="flex-1 focus:outline-none">
        <Outlet />
      </main>
      <PublicFooter landing={landing} />
      <ScrollRestoration />
    </div>
  );
}

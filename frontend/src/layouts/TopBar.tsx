import { Menu as MenuIcon, Plus, Search } from 'lucide-react';
import { Link } from 'react-router-dom';
import { buttonClasses, IconButton } from '@/components/ui/Button';
import { BrandMark } from '@/design-system/BrandMark';
import { brand } from '@/design-system/brand';
import { useAuth } from '@/features/auth/hooks';
import { MessagesLink } from '@/features/messages/components/MessagesLink';
import { NotificationBell } from '@/features/notifications/components/NotificationBell';
import { SearchBox } from '@/features/search/components/SearchBox';
import { ThemeToggle } from './ThemeToggle';
import { UserMenu } from './UserMenu';

export function TopBar({ onOpenDrawer, onOpenSearch }: { onOpenDrawer: () => void; onOpenSearch: () => void }) {
  const { isAuthed, status } = useAuth();
  return (
    <header className="sticky top-0 z-30 border-b border-border bg-bg">
      <div className="flex h-14 items-center gap-2 px-3 md:px-4">
        <IconButton label="Open navigation" size="sm" className="md:hidden" onClick={onOpenDrawer}>
          <MenuIcon size={19} />
        </IconButton>
        <Link to={isAuthed ? '/home' : '/'} aria-label={`${brand.name} home`} className="shrink-0 xl:w-[240px]">
          <span className="hidden lg:inline"><BrandMark withName /></span>
          <span className="lg:hidden"><BrandMark /></span>
        </Link>
        <div className="mx-auto hidden w-full max-w-xl md:block">
          <SearchBox />
        </div>
        <div className="ml-auto flex items-center gap-1 md:ml-0">
          <IconButton label="Search" size="sm" className="md:hidden" onClick={onOpenSearch}>
            <Search size={18} />
          </IconButton>
          {status === 'booting' ? null : isAuthed ? (
            <>
              <Link to="/submit" className={buttonClasses('ghost', 'sm', 'hidden h-10 px-3 text-text sm:inline-flex')}>
                <Plus size={20} /> Create
              </Link>
              <MessagesLink />
              <NotificationBell />
              <UserMenu />
            </>
          ) : (
            <>
              <ThemeToggle />
              <Link to="/login" className={buttonClasses('ghost', 'sm')}>Log in</Link>
              <Link to="/register" className={buttonClasses('primary', 'sm')}>Join</Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
}

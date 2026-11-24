import { Bell, Compass, Home, PlusSquare, User } from 'lucide-react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '@/features/auth/hooks';
import { useUnreadCount } from '@/features/notifications/hooks';
import { cn } from '@/utils/format';

export function MobileTabBar() {
  const { user } = useAuth();
  const unread = useUnreadCount().data?.count ?? 0;
  const items = [
    { to: '/home', label: 'Home', icon: Home },
    { to: '/explore', label: 'Explore', icon: Compass },
    { to: user ? '/submit' : '/login?next=/submit', label: 'Create', icon: PlusSquare },
    { to: user ? '/notifications' : '/login?next=/notifications', label: 'Inbox', icon: Bell, badge: unread },
    { to: user ? `/u/${user.username}` : '/login', label: 'You', icon: User },
  ];
  return (
    <nav
      aria-label="Tabs"
      className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-bg/95 pb-[env(safe-area-inset-bottom)] backdrop-blur md:hidden"
    >
      <ul className="grid grid-cols-5">
        {items.map(({ to, label, icon: Icon, badge }) => (
          <li key={label}>
            <NavLink
              to={to}
              className={({ isActive }) =>
                cn('relative flex h-14 flex-col items-center justify-center gap-0.5 text-[11px]', isActive ? 'text-text' : 'text-muted')
              }
            >
              {({ isActive }) => (
                <>
                  <Icon size={20} className={cn(isActive && 'text-accent')} />
                  {label}
                  {!!badge && (
                    <span className="absolute right-[calc(50%-18px)] top-2 h-2 w-2 rounded-full bg-accent" aria-label={`${badge} unread`} />
                  )}
                </>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}

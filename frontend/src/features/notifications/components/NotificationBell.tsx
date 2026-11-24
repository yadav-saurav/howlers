import { Bell } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cn } from '@/utils/format';
import { useUnreadCount } from '../hooks';

export function NotificationBell({ className }: { className?: string }) {
  const { data } = useUnreadCount();
  const count = data?.count ?? 0;
  return (
    <Link
      to="/notifications"
      aria-label={count ? `Notifications, ${count} unread` : 'Notifications'}
      className={cn(
        'relative inline-flex h-9 w-9 items-center justify-center rounded-md text-muted hover:bg-surface-2 hover:text-text',
        className,
      )}
    >
      <Bell size={18} />
      {count > 0 && (
        <span className="absolute right-1 top-1 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-accent px-1 text-[10px] font-bold text-accent-fg">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Link>
  );
}

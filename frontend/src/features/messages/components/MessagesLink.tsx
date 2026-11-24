import { MessagesSquare } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cn } from '@/utils/format';
import { useDmUnreadCount } from '../api';

/** Top-bar entry to Messages with the unread-message count (pushed via UNREAD_MESSAGES). */
export function MessagesLink({ className }: { className?: string }) {
  const { data } = useDmUnreadCount();
  const count = data?.count ?? 0;
  return (
    <Link
      to="/messages"
      aria-label={count ? `Messages, ${count} unread` : 'Messages'}
      title="Messages"
      className={cn('relative inline-flex h-9 w-9 items-center justify-center rounded-md text-muted hover:bg-surface-2 hover:text-text', className)}
    >
      <MessagesSquare size={18} />
      {count > 0 && (
        <span className="absolute right-1 top-1 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-accent px-1 text-[10px] font-bold text-accent-fg">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Link>
  );
}

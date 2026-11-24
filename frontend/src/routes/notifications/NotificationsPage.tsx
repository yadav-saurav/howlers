import { AtSign, Award, Ban, Bell, BarChart3, CheckCheck, Gavel, Hourglass, MessageSquare, ShieldAlert, ShieldCheck, SmilePlus, UserPlus, type LucideIcon } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Card, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { Segmented } from '@/components/ui/Tabs';
import { toast } from '@/components/ui/Toast';
import { useMarkAllRead, useMarkRead, useNotifications } from '@/features/notifications/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import { errorMessage } from '@/services/apiError';
import type { NotificationType } from '@/types/api';
import { cn, timeAgo } from '@/utils/format';

const ICONS: Record<NotificationType, LucideIcon> = {
  POST_REPLY: MessageSquare,
  COMMENT_REPLY: MessageSquare,
  MENTION: AtSign,
  NEW_FOLLOWER: UserPlus,
  POST_MILESTONE: Award,
  COMMUNITY_ROLE: ShieldCheck,
  CONTENT_REMOVED: ShieldAlert,
  BANNED: Ban,
  APPEAL_RESOLVED: Gavel,
  PULSE_RESULT: BarChart3,
  REACTION: SmilePlus,
  AUTOMOD_HELD: Hourglass,
};

export default function NotificationsPage() {
  useDocumentTitle('Notifications');
  const [filter, setFilter] = useState<'all' | 'unread'>('all');
  const q = useNotifications(filter === 'unread');
  const markRead = useMarkRead();
  const markAll = useMarkAllRead();
  const navigate = useNavigate();
  const items = q.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <PageColumns>
      <PageHeader
        title="Notifications"
        actions={
          <Button variant="outline" size="sm" loading={markAll.isPending} onClick={() => markAll.mutate(undefined, { onSuccess: () => toast.success('All caught up'), onError: (e) => toast.error(errorMessage(e)) })}>
            <CheckCheck size={15} /> Mark all read
          </Button>
        }
      />
      <div className="mb-3 px-3 sm:px-0">
        <Segmented label="Filter" value={filter} onChange={setFilter} options={[{ value: 'all', label: 'All' }, { value: 'unread', label: 'Unread' }]} />
      </div>
      <Card className="rounded-none border-x-0 sm:rounded-lg sm:border-x">
        {q.isPending && <div className="space-y-3 p-4">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-12" />)}</div>}
        {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
        {q.data && items.length === 0 && (
          <EmptyState icon={Bell} title={filter === 'unread' ? 'All caught up' : 'No notifications yet'} description={filter === 'unread' ? 'No unread notifications. Replies, mentions and new followers show up here.' : 'All quiet in the den. Replies, mentions and new followers show up here.'} mascot={filter === 'unread' ? 'howl' : 'sleepy'} />
        )}
        {items.length > 0 && (
          <ul className="divide-y divide-border">
            {items.map((n) => {
              const Icon = ICONS[n.type] ?? Bell;
              return (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => {
                      if (!n.read) markRead.mutate(n.id);
                      navigate(n.link);
                    }}
                    className={cn('flex w-full items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-surface-2/50', !n.read && 'bg-accent/5')}
                  >
                    <span className="relative">
                      {n.actor ? <Avatar src={n.actor.avatarUrl} name={n.actor.username} /> : <span className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-surface-2 text-muted"><Icon size={16} /></span>}
                      {n.actor && (
                        <span className="absolute -bottom-1 -right-1 inline-flex h-5 w-5 items-center justify-center rounded-full border-2 border-surface bg-surface-2 text-muted"><Icon size={10} /></span>
                      )}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm">{n.message}{n.count > 1 && <span className="text-muted"> and {n.count - 1} more</span>}</span>
                      <span className="text-xs text-faint">{timeAgo(n.createdAt)}</span>
                    </span>
                    {!n.read && <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-accent" aria-label="Unread" />}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
      </Card>
    </PageColumns>
  );
}

import { Ban, MessagesSquare, MoreVertical, Search, Star, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { Menu, MenuContent, MenuItem, MenuTrigger } from '@/components/ui/Menu';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useTypingUsers } from '@/features/realtime/typingStore';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { errorMessage } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import type { ConversationSummary } from '@/types/messages';
import { cn, displayName, timeAgo } from '@/utils/format';
import { useConversations, useToggleFavorite } from '../api';
import { INBOX_FILTERS, emptyFilterText, filterConversations, unreadConversations, type InboxFilter } from '../chat';
import { ConnectionHint, OnlineDot, RoomAvatar } from './bits';

function Row({ c, active, meId, onFavorite }: { c: ConversationSummary; active: boolean; meId: number | undefined; onFavorite: () => void }) {
  const typing = useTypingUsers(c.id).length > 0;
  const room = c.kind === 'ROOM' ? c.room : null;
  const name = room ? room.name : c.other ? displayName(c.other) : 'Deleted account';
  const last = c.lastMessage;
  const excerpt = !last ? 'No messages yet' : last.deleted ? 'Message deleted' : `${last.senderId === meId ? 'You: ' : ''}${last.excerpt || 'Attachment'}`;
  const unread = c.unreadCount > 0;
  return (
    <li className="group relative">
      <Link
        to={`/messages/${c.id}`}
        aria-current={active ? 'page' : undefined}
        className={cn(
          'flex items-center gap-3 rounded-xl px-2.5 py-2.5 pr-9 transition-colors hover:bg-surface-2 focus-visible:bg-surface-2',
          active && 'bg-surface-2',
        )}
      >
        <span className="relative shrink-0">
          {room ? <RoomAvatar icon={room.iconEmoji} /> : <Avatar src={c.other?.avatarUrl} name={c.other?.username ?? '?'} size="md" />}
          {c.online && <OnlineDot className="absolute -bottom-0.5 -right-0.5" />}
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline gap-1.5">
            <span className={cn('truncate text-[15px]', unread ? 'font-bold' : 'font-semibold')}>{name}</span>
            {c.favorite && <Star size={12} className="shrink-0 translate-y-px text-warning" fill="currentColor" aria-label="Favourite" role="img" />}
            {room && (
              <span className="shrink-0 rounded-full bg-accent/15 px-1.5 text-[10px] font-semibold text-accent" title={`Pack room with ${room.memberCount} members`}>
                Pack · {room.memberCount}
              </span>
            )}
            {last && <span className={cn('ml-auto shrink-0 text-[11px]', unread ? 'font-semibold text-accent' : 'text-faint')}>{timeAgo(last.createdAt)}</span>}
          </span>
          <span className="mt-0.5 flex items-center gap-2">
            {typing ? (
              <span className="truncate text-[13px] font-medium text-accent">typing…</span>
            ) : (
              <span className={cn('flex min-w-0 items-center gap-1 truncate text-[13px]', unread ? 'font-semibold text-text' : 'text-muted', last?.deleted && 'italic')}>
                {last?.deleted && <Ban size={12} aria-hidden="true" className="shrink-0" />}
                <span className="truncate">{excerpt}</span>
              </span>
            )}
            {unread && (
              <span className="ml-auto inline-flex h-5 min-w-5 shrink-0 items-center justify-center rounded-full bg-accent px-1.5 text-[11px] font-bold text-accent-fg" aria-label={`${c.unreadCount} unread`}>
                {c.unreadCount > 99 ? '99+' : c.unreadCount}
              </span>
            )}
          </span>
        </span>
      </Link>
      <div className="absolute right-1.5 top-1/2 -translate-y-1/2 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100 has-[[aria-expanded=true]]:opacity-100 [@media(hover:none)]:opacity-100">
        <Menu>
          <MenuTrigger asChild>
            <button type="button" aria-label={`Options for ${name}`} className="inline-flex h-7 w-7 items-center justify-center rounded-full text-muted hover:bg-surface-3 hover:text-text">
              <MoreVertical size={15} />
            </button>
          </MenuTrigger>
          <MenuContent>
            <MenuItem icon={<Star />} onSelect={onFavorite}>{c.favorite ? 'Remove from favourites' : 'Add to favourites'}</MenuItem>
          </MenuContent>
        </Menu>
      </div>
    </li>
  );
}

/** WhatsApp-style inbox: a pill search, then All / Unread / Favourites / Rooms chips over the conversation list. */
export function ConversationList({ activeId, q, onQueryChange }: { activeId: number | null; q: string; onQueryChange: (q: string) => void }) {
  const list = useConversations(useDebouncedValue(q.trim(), 250));
  const meId = useAuthStore((s) => s.user?.id);
  const fav = useToggleFavorite();
  const [filter, setFilter] = useState<InboxFilter>('all');
  const all = useMemo(() => list.data ?? [], [list.data]);
  const shown = useMemo(() => filterConversations(all, filter), [all, filter]);
  const unread = unreadConversations(all);

  const toggleFav = (c: ConversationSummary) =>
    fav.mutate({ id: c.id, favorite: !c.favorite }, { onError: (e) => toast.error(errorMessage(e)) });

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="space-y-2.5 px-3 pb-2 pt-3">
        <div className="flex items-center gap-2">
          <h1 className="text-xl font-bold tracking-tight">Messages</h1>
          <ConnectionHint className="ml-1" />
        </div>
        <label className="relative block">
          <span className="sr-only">Search conversations</span>
          <Search size={16} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint" aria-hidden="true" />
          <input
            type="search"
            value={q}
            onChange={(e) => onQueryChange(e.target.value)}
            placeholder="Search"
            className="h-10 w-full rounded-full border border-transparent bg-surface-2 pl-10 pr-9 text-sm outline-none transition-colors placeholder:text-faint focus:border-accent focus:bg-surface [&::-webkit-search-cancel-button]:hidden"
          />
          {q && (
            <button type="button" onClick={() => onQueryChange('')} aria-label="Clear search" className="absolute right-2 top-1/2 inline-flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-full text-muted hover:bg-surface-3 hover:text-text">
              <X size={14} />
            </button>
          )}
        </label>
        <div role="group" aria-label="Filter conversations" className="flex flex-wrap gap-1.5">
          {INBOX_FILTERS.map((f) => {
            const on = filter === f.id;
            return (
              <button
                key={f.id}
                type="button"
                aria-pressed={on}
                onClick={() => setFilter(f.id)}
                className={cn(
                  'inline-flex h-8 shrink-0 items-center gap-1.5 rounded-full px-3 text-[13px] font-medium transition-colors',
                  on ? 'bg-accent/15 text-accent ring-1 ring-accent/40' : 'bg-surface-2 text-muted hover:bg-surface-3 hover:text-text',
                )}
              >
                {f.label}
                {f.id === 'unread' && unread > 0 && (
                  <span className={cn('rounded-full px-1.5 text-[11px] font-bold', on ? 'bg-accent text-accent-fg' : 'bg-surface-3 text-text')}>
                    {unread > 99 ? '99+' : unread}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-1.5 pb-2">
        {list.isPending ? (
          <ul aria-busy="true" className="space-y-1 p-2">
            {Array.from({ length: 6 }, (_, i) => (
              <li key={i} className="flex items-center gap-3 py-1.5">
                <Skeleton className="h-9 w-9 rounded-full" />
                <span className="flex-1 space-y-1.5"><Skeleton className="h-3 w-1/2" /><Skeleton className="h-3 w-3/4" /></span>
              </li>
            ))}
          </ul>
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => list.refetch()} />
        ) : all.length === 0 && !q ? (
          <EmptyState
            icon={MessagesSquare}
            title="No conversations yet"
            description="Open someone’s profile and choose Message to start a conversation."
            mascot="wave"
          />
        ) : shown.length === 0 ? (
          <p className="px-4 py-10 text-center text-sm text-muted">
            {q ? `No ${filter === 'all' ? '' : `${INBOX_FILTERS.find((f) => f.id === filter)!.label.toLowerCase()} `}conversations match “${q}”.` : emptyFilterText(filter)}
          </p>
        ) : (
          <ul aria-label="Conversations">
            {shown.map((c) => <Row key={c.id} c={c} active={c.id === activeId} meId={meId} onFavorite={() => toggleFav(c)} />)}
          </ul>
        )}
      </div>
    </div>
  );
}

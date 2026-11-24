import { MessagesSquare, PenSquare, Search, Users } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useAuthStore } from '@/store/authStore';
import type { ConversationSummary } from '@/types/messages';
import { cn, displayName, timeAgo } from '@/utils/format';
import { useConversations } from '../api';
import { CreateRoomDialog } from './CreateRoomDialog';
import { NewMessageDialog } from './NewMessageDialog';
import { ConnectionHint, OnlineDot, RoomAvatar } from './bits';

function Row({ c, active, meId }: { c: ConversationSummary; active: boolean; meId: number | undefined }) {
  const room = c.kind === 'ROOM' ? c.room : null;
  const name = room ? room.name : c.other ? displayName(c.other) : 'Deleted account';
  const last = c.lastMessage;
  const excerpt = !last ? 'No messages yet' : last.deleted ? 'Message deleted' : `${last.senderId === meId ? 'You: ' : ''}${last.excerpt}`;
  const unread = c.unreadCount > 0;
  return (
    <li>
      <Link
        to={`/messages/${c.id}`}
        aria-current={active ? 'page' : undefined}
        className={cn('flex items-center gap-3 px-3 py-2.5 transition-colors hover:bg-surface-2', active && 'bg-surface-2')}
      >
        <span className="relative shrink-0">
          {room ? <RoomAvatar icon={room.iconEmoji} /> : <Avatar src={c.other?.avatarUrl} name={c.other?.username ?? '?'} size="md" />}
          {c.online && <OnlineDot className="absolute -bottom-0.5 -right-0.5" />}
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline gap-2">
            <span className={cn('truncate text-sm', unread ? 'font-bold' : 'font-medium')}>{name}</span>
            {room && (
              <span className="shrink-0 rounded-full bg-accent/15 px-1.5 text-[10px] font-semibold text-accent" title={`Pack room with ${room.memberCount} members`}>
                Pack · {room.memberCount}
              </span>
            )}
            {last && <span className="ml-auto shrink-0 text-[11px] text-faint">{timeAgo(last.createdAt)}</span>}
          </span>
          <span className="flex items-center gap-2">
            <span className={cn('truncate text-xs', unread ? 'font-semibold text-text' : 'text-muted', last?.deleted && 'italic')}>{excerpt}</span>
            {unread && (
              <span className="ml-auto inline-flex h-4 min-w-4 shrink-0 items-center justify-center rounded-full bg-accent px-1 text-[10px] font-bold text-accent-fg" aria-label={`${c.unreadCount} unread`}>
                {c.unreadCount > 99 ? '99+' : c.unreadCount}
              </span>
            )}
          </span>
        </span>
      </Link>
    </li>
  );
}

export function ConversationList({ activeId, q, onQueryChange }: { activeId: number | null; q: string; onQueryChange: (q: string) => void }) {
  const list = useConversations(useDebouncedValue(q.trim(), 250));
  const meId = useAuthStore((s) => s.user?.id);
  const [composing, setComposing] = useState(false);
  const [creatingRoom, setCreatingRoom] = useState(false);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b border-border px-3 py-2.5">
        <h1 className="text-lg font-bold">Messages</h1>
        <ConnectionHint className="ml-1" />
        <Button size="sm" variant="ghost" className="ml-auto text-text" onClick={() => setCreatingRoom(true)} aria-label="New pack room">
          <Users size={16} /> Room
        </Button>
        <Button size="sm" variant="ghost" className="text-text" onClick={() => setComposing(true)} aria-label="Start a new message">
          <PenSquare size={16} /> New
        </Button>
      </div>
      <div className="border-b border-border p-2">
        <label className="relative block">
          <span className="sr-only">Search messages</span>
          <Search size={15} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-faint" />
          <input
            type="search"
            value={q}
            onChange={(e) => onQueryChange(e.target.value)}
            placeholder="Search people or messages"
            className="h-9 w-full rounded-full border border-border bg-surface-2 pl-8 pr-3 text-sm outline-none placeholder:text-faint focus:border-accent"
          />
        </label>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {list.isPending ? (
          <ul aria-busy="true" className="space-y-1 p-3">
            {Array.from({ length: 6 }, (_, i) => (
              <li key={i} className="flex items-center gap-3 py-1.5">
                <Skeleton className="h-9 w-9 rounded-full" />
                <span className="flex-1 space-y-1.5"><Skeleton className="h-3 w-1/2" /><Skeleton className="h-3 w-3/4" /></span>
              </li>
            ))}
          </ul>
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => list.refetch()} />
        ) : list.data.length === 0 ? (
          q ? (
            <p className="px-4 py-10 text-center text-sm text-muted">No conversations match “{q}”.</p>
          ) : (
            <EmptyState
              icon={MessagesSquare}
              title="No conversations yet"
              description="Say hello to someone from your pack. Start a one-to-one conversation with anyone in the community."
              mascot="wave"
              action={<Button size="sm" onClick={() => setComposing(true)}>New message</Button>}
            />
          )
        ) : (
          <ul>
            {list.data.map((c) => <Row key={c.id} c={c} active={c.id === activeId} meId={meId} />)}
          </ul>
        )}
      </div>
      <NewMessageDialog open={composing} onOpenChange={setComposing} />
      <CreateRoomDialog open={creatingRoom} onOpenChange={setCreatingRoom} />
    </div>
  );
}

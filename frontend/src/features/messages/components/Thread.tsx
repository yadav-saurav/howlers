import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, MoreHorizontal, RotateCcw, Trash2, Users } from 'lucide-react';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { Menu, MenuContent, MenuItem, MenuTrigger } from '@/components/ui/Menu';
import { Spinner } from '@/components/ui/Spinner';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useTypingUsers } from '@/features/realtime/typingStore';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { errorMessage } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import type { DmMedia, DmMessage } from '@/types/messages';
import { responsiveImage } from '@/utils/images';
import { cn, displayName } from '@/utils/format';
import { AddReactionButton, ReactionBar } from '@/features/reactions/components/ReactionBar';
import { cacheMessage, dmApi, dmKeys, useConversation, useDeleteMessage, useMarkRead, useMessages, useRoom } from '../api';
import { flattenPages, groupMessages, seenMessageId } from '../helpers';
import { newestOwnMessageId, seenByCount, seenByLabel, typingLabel } from '../rooms';
import { ConnectionHint, OnlineDot, RoomAvatar } from './bits';
import { Composer } from './Composer';
import { RoomPanel } from './RoomPanel';

interface Pending {
  tempId: string;
  body: string;
  files: File[];
  previews: string[];
  mediaIds: number[] | null;
  status: 'sending' | 'failed';
  error?: string;
}

/**
 * A DM image. Signed URLs expire (30 to 60 min), so a failed load refetches the thread once for fresh URLs instead of
 * leaving a broken image behind.
 */
function DmImage({ img, conversationId }: { img: DmMedia; conversationId: number }) {
  const qc = useQueryClient();
  const retried = useRef(false);
  const r = responsiveImage(img);
  return (
    <a href={img.url} target="_blank" rel="noreferrer" className="block overflow-hidden rounded-xl bg-surface-2">
      <img
        src={r.src}
        srcSet={r.srcSet}
        sizes="(min-width: 768px) 320px, 70vw"
        alt="Attachment"
        loading="lazy"
        decoding="async"
        width={img.width ?? undefined}
        height={img.height ?? undefined}
        className="max-h-64 w-full object-cover"
        onError={() => {
          if (!img.signed || retried.current) return;
          retried.current = true;
          void qc.invalidateQueries({ queryKey: dmKeys.messages(conversationId) });
        }}
      />
    </a>
  );
}

const time = (iso: string) => new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });

function Bubble({ m, mine, onDelete }: { m: DmMessage; mine: boolean; onDelete: () => void }) {
  if (m.deleted) {
    return (
      <div className={cn('flex', mine && 'justify-end')}>
        <span className="rounded-2xl border border-dashed border-border px-3 py-1.5 text-sm italic text-faint" title={time(m.createdAt)}>Message deleted</span>
      </div>
    );
  }
  if (m.fromBlocked) {
    // Pack rooms: the server withholds the text and images of people you blocked (D-043).
    return (
      <div className="flex">
        <span className="rounded-2xl border border-dashed border-border px-3 py-1.5 text-xs italic text-faint" title={time(m.createdAt)}>Message from someone you blocked</span>
      </div>
    );
  }
  return (
    <div className={cn('group flex items-center gap-1', mine && 'flex-row-reverse')}>
      <div className={cn('max-w-[78%] space-y-1', mine && 'flex flex-col items-end')} title={time(m.createdAt)}>
        {m.media.length > 0 && (
          <div className={cn('grid gap-1', m.media.length > 1 && 'grid-cols-2')}>
            {m.media.map((img) => <DmImage key={img.id} img={img} conversationId={m.conversationId} />)}
          </div>
        )}
        {m.body && (
          <p className={cn('whitespace-pre-wrap break-words rounded-2xl px-3 py-1.5 text-sm leading-relaxed', mine ? 'bg-brand text-brand-fg' : 'bg-surface-2 text-text')}>
            {m.body}
          </p>
        )}
        <ReactionBar type="MESSAGE" id={m.id} compact align={mine ? 'end' : 'start'} withPicker={false} />
      </div>
      <AddReactionButton type="MESSAGE" id={m.id} compact popupAlign={mine ? 'end' : 'start'}
        className="opacity-0 focus-within:opacity-100 group-hover:opacity-100 has-[[aria-expanded=true]]:opacity-100" />
      {mine && (
        <Menu>
          <MenuTrigger asChild>
            <button type="button" aria-label="Message options" className="rounded-full p-1 text-faint opacity-0 hover:bg-surface-2 hover:text-text focus:opacity-100 group-hover:opacity-100">
              <MoreHorizontal size={15} />
            </button>
          </MenuTrigger>
          <MenuContent align={mine ? 'end' : 'start'}>
            <MenuItem danger icon={<Trash2 />} onSelect={onDelete}>Delete for everyone</MenuItem>
          </MenuContent>
        </Menu>
      )}
    </div>
  );
}

export function Thread({ id }: { id: number }) {
  const qc = useQueryClient();
  const meId = useAuthStore((s) => s.user?.id);
  const conv = useConversation(id);
  const msgs = useMessages(id);
  const markRead = useMarkRead(id);
  const del = useDeleteMessage(id);
  const typers = useTypingUsers(id);
  const [pending, setPending] = useState<Pending[]>([]);
  const scroller = useRef<HTMLDivElement>(null);
  const c = conv.data;
  const other = c?.other ?? null;
  const isRoom = c?.kind === 'ROOM';
  const room = useRoom(id, isRoom);
  const [panelOpen, setPanelOpen] = useState(false);
  const members = useMemo(() => room.data?.members ?? [], [room.data]);
  const memberById = useMemo(() => new Map(members.map((m) => [m.user.id, m.user])), [members]);
  useDocumentTitle(isRoom && c?.room ? `${c.room.name} · Messages` : other ? `${displayName(other)} · Messages` : 'Messages');

  const messages = useMemo(() => flattenPages(msgs.data), [msgs.data]);
  const days = useMemo(() => groupMessages(messages), [messages]);
  const seen = isRoom ? null : seenMessageId(messages, meId, c?.otherLastReadMessageId ?? null);
  // Pack rooms: one aggregated receipt under my newest message ("Seen by 3").
  const roomSeenId = isRoom ? newestOwnMessageId(messages, meId) : null;
  const roomSeen = roomSeenId == null ? null
    : seenByLabel(seenByCount(roomSeenId, meId ?? null, members, meId), Math.max(0, members.length - 1));
  const typingNames = typers.map((u) => memberById.get(u)?.username ?? 'Someone');
  const newest = messages[messages.length - 1];

  /* ---------- scrolling: stick to bottom for new messages, keep position when older ones load */
  const atBottom = useRef(true);
  const prevFirst = useRef<number | null>(null);
  const prevHeight = useRef(0);
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const first = messages[0]?.id ?? null;
    if (prevFirst.current != null && first != null && first < prevFirst.current) {
      el.scrollTop += el.scrollHeight - prevHeight.current; // older page prepended
    } else if (atBottom.current) {
      el.scrollTop = el.scrollHeight;
    }
    prevFirst.current = first;
    prevHeight.current = el.scrollHeight;
  }, [messages, pending.length, typers.length]);
  useEffect(() => {
    atBottom.current = true;
    prevFirst.current = null;
  }, [id]);

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    prevHeight.current = el.scrollHeight;
    if (el.scrollTop < 120 && msgs.hasNextPage && !msgs.isFetchingNextPage) void msgs.fetchNextPage();
  };

  /* ---------- read receipts: mark the newest message read while the thread is visible */
  const lastMarked = useRef(0);
  useEffect(() => {
    lastMarked.current = 0;
  }, [id]);
  const markIfVisible = useCallback(() => {
    if (!newest || document.visibilityState !== 'visible') return;
    if (newest.id <= lastMarked.current) return;
    const needed = (c?.unreadCount ?? 0) > 0 || newest.senderId !== meId;
    if (!needed) return;
    lastMarked.current = newest.id;
    markRead.mutate(newest.id);
  }, [newest, c?.unreadCount, meId, markRead]);
  useEffect(() => {
    markIfVisible();
    document.addEventListener('visibilitychange', markIfVisible);
    return () => document.removeEventListener('visibilitychange', markIfVisible);
  }, [markIfVisible]);

  /* ---------- optimistic send with retry */
  const runSend = useCallback(
    async (p: Pending) => {
      setPending((ps) => ps.map((x) => (x.tempId === p.tempId ? { ...x, status: 'sending', error: undefined } : x)));
      let mediaIds = p.mediaIds;
      try {
        if (!mediaIds) {
          mediaIds = [];
          for (const f of p.files) mediaIds.push((await dmApi.uploadMedia(f)).id);
          const ids = mediaIds;
          setPending((ps) => ps.map((x) => (x.tempId === p.tempId ? { ...x, mediaIds: ids } : x)));
        }
        const msg = await dmApi.send(id, p.body, mediaIds);
        cacheMessage(qc, msg);
        atBottom.current = true;
        setPending((ps) => ps.filter((x) => x.tempId !== p.tempId));
        p.previews.forEach((u) => URL.revokeObjectURL(u));
        void qc.invalidateQueries({ queryKey: dmKeys.lists });
      } catch (e) {
        const ids = mediaIds;
        setPending((ps) => ps.map((x) => (x.tempId === p.tempId ? { ...x, status: 'failed', error: errorMessage(e), mediaIds: ids } : x)));
      }
    },
    [id, qc],
  );
  const send = (body: string, files: File[]) => {
    const p: Pending = {
      tempId: `t${Date.now()}${Math.random().toString(36).slice(2, 6)}`,
      body,
      files,
      previews: files.map((f) => URL.createObjectURL(f)),
      mediaIds: files.length ? null : [],
      status: 'sending',
    };
    atBottom.current = true;
    setPending((ps) => [...ps, p]);
    void runSend(p);
  };
  const discard = (p: Pending) => {
    p.previews.forEach((u) => URL.revokeObjectURL(u));
    setPending((ps) => ps.filter((x) => x.tempId !== p.tempId));
  };

  const onDelete = (messageId: number) =>
    del.mutate(messageId, { onError: (e) => toast.error(errorMessage(e)) });

  if (conv.isError) {
    return (
      <div className="flex h-full flex-col">
        <div className="border-b border-border px-3 py-2.5 md:hidden"><Link to="/messages" className="inline-flex items-center gap-1.5 text-sm text-muted"><ArrowLeft size={15} /> Messages</Link></div>
        <ErrorState error={conv.error} onRetry={() => conv.refetch()} title="Could not open this conversation" />
      </div>
    );
  }

  const otherName = isRoom ? c?.room?.name ?? 'Pack room' : other ? displayName(other) : 'Deleted account';
  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-3 border-b border-border px-3 py-2">
        <Link to="/messages" aria-label="Back to conversations" className="rounded-full p-1.5 text-muted hover:bg-surface-2 hover:text-text md:hidden">
          <ArrowLeft size={18} />
        </Link>
        {c && isRoom && c.room ? (
          <>
            <RoomAvatar icon={c.room.iconEmoji} />
            <div className="min-w-0 flex-1">
              <span className="block truncate text-sm font-semibold">{c.room.name}</span>
              <span className="block truncate text-xs text-muted">
                {typingLabel(typingNames) ?? `${c.room.memberCount} members${c.online ? ' · someone is online' : ''}`}
              </span>
            </div>
            <button type="button" onClick={() => setPanelOpen(true)} aria-label="Members and room settings"
              className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1.5 text-sm text-muted hover:bg-surface-2 hover:text-text">
              <Users size={16} /> <span className="hidden sm:inline">Members</span>
            </button>
            <RoomPanel id={id} open={panelOpen} onOpenChange={setPanelOpen} />
          </>
        ) : c ? (
          <>
            <span className="relative shrink-0">
              <Avatar src={other?.avatarUrl} name={other?.username ?? '?'} size="md" />
              {c.online && <OnlineDot className="absolute -bottom-0.5 -right-0.5" />}
            </span>
            <div className="min-w-0 flex-1">
              {other ? (
                <Link to={`/u/${other.username}`} className="block truncate text-sm font-semibold hover:underline">{otherName}</Link>
              ) : (
                <span className="block truncate text-sm font-semibold text-muted">{otherName}</span>
              )}
              <span className="block truncate text-xs text-muted">
                {typers.length ? 'typing…' : c.online ? 'Online' : other ? `u/${other.username}` : ''}
              </span>
            </div>
          </>
        ) : (
          <div className="flex flex-1 items-center gap-3"><Skeleton className="h-9 w-9 rounded-full" /><Skeleton className="h-4 w-32" /></div>
        )}
        <ConnectionHint />
      </header>

      <div ref={scroller} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto px-3 py-3" role="log" aria-label={`Conversation with ${otherName}`} aria-live="polite" aria-relevant="additions">
        {msgs.isPending ? (
          <div className="space-y-3" aria-busy="true">
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i} className={cn('flex', i % 2 && 'justify-end')}><Skeleton className="h-8 w-48 rounded-2xl" /></div>
            ))}
          </div>
        ) : msgs.isError ? (
          <ErrorState error={msgs.error} onRetry={() => msgs.refetch()} />
        ) : (
          <>
            {msgs.isFetchingNextPage && <div className="flex justify-center py-2"><Spinner size={16} /></div>}
            {!msgs.hasNextPage && messages.length > 0 && (
              <p className="py-3 text-center text-xs text-faint">
                {isRoom ? `This is the start of ${otherName}.` : `This is the start of your conversation with ${otherName}.`}
              </p>
            )}
            {messages.length === 0 && pending.length === 0 && (
              <p className="py-10 text-center text-sm text-muted">No messages yet. Say hello.</p>
            )}
            {days.map((day) => (
              <section key={day.key} aria-label={day.label}>
                <div className="my-3 flex items-center gap-3 text-[11px] font-medium text-faint">
                  <span className="h-px flex-1 bg-border" />{day.label}<span className="h-px flex-1 bg-border" />
                </div>
                {day.groups.map((g) => {
                  if (g.system) {
                    const s = g.messages[0];
                    return (
                      <p key={s.id} className="mb-2.5 px-6 text-center text-xs text-muted" title={time(s.createdAt)}>{s.body}</p>
                    );
                  }
                  const mine = g.senderId === meId;
                  const lastMsg = g.messages[g.messages.length - 1];
                  const sender = isRoom && !mine && !g.messages[0].fromBlocked ? memberById.get(g.senderId) : undefined;
                  return (
                    <div key={g.messages[0].id} className="mb-2.5 space-y-0.5">
                      {isRoom && !mine && !g.messages[0].fromBlocked && (
                        <p className="flex items-center gap-1.5 px-1 text-[11px] font-semibold text-muted">
                          {sender && <Avatar src={sender.avatarUrl} name={sender.username} size="xs" />}
                          {sender ? displayName(sender) : 'Former member'}
                        </p>
                      )}
                      {g.messages.map((m) => (
                        <div key={m.id}>
                          <Bubble m={m} mine={mine} onDelete={() => onDelete(m.id)} />
                          {m.id === seen && m.id !== lastMsg.id && <p className="mt-0.5 text-right text-[11px] text-faint">Seen</p>}
                        </div>
                      ))}
                      <p className={cn('px-1 text-[11px] text-faint', mine && 'text-right')}>
                        {time(lastMsg.createdAt)}
                        {lastMsg.id === seen && ' · Seen'}
                        {roomSeen && g.messages.some((m) => m.id === roomSeenId) && ` · ${roomSeen}`}
                      </p>
                    </div>
                  );
                })}
              </section>
            ))}
            {pending.map((p) => (
              <div key={p.tempId} className="mb-2 flex flex-col items-end gap-0.5">
                <div className={cn('max-w-[78%] space-y-1', p.status === 'sending' && 'opacity-70')}>
                  {p.previews.length > 0 && (
                    <div className={cn('grid gap-1', p.previews.length > 1 && 'grid-cols-2')}>
                      {p.previews.map((u) => <img key={u} src={u} alt="Attachment preview" className="max-h-64 w-full rounded-xl object-cover" />)}
                    </div>
                  )}
                  {p.body && <p className="whitespace-pre-wrap break-words rounded-2xl bg-brand px-3 py-1.5 text-sm text-brand-fg">{p.body}</p>}
                </div>
                {p.status === 'sending' ? (
                  <span className="px-1 text-[11px] text-faint">Sending…</span>
                ) : (
                  <span className="flex items-center gap-2 px-1 text-[11px] text-danger" role="alert">
                    Not sent{p.error ? `: ${p.error}` : ''}
                    <button type="button" onClick={() => void runSend(p)} className="inline-flex items-center gap-0.5 font-semibold underline"><RotateCcw size={11} /> Retry</button>
                    <button type="button" onClick={() => discard(p)} className="font-semibold text-muted underline">Discard</button>
                  </span>
                )}
              </div>
            ))}
            {typers.length > 0 && (
              <p className="px-1 text-xs italic text-muted" aria-live="polite">{isRoom ? typingLabel(typingNames) : `${otherName} is typing…`}</p>
            )}
          </>
        )}
      </div>

      {c && <Composer conversationId={id} disabledReason={c.canMessage ? null : c.cannotMessageReason ?? 'You can’t message this person.'} onSend={send} />}
    </div>
  );
}

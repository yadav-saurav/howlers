import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ChevronDown, Mic, Palette, RotateCcw, Star, Users } from 'lucide-react';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { Spinner } from '@/components/ui/Spinner';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useTypingUsers } from '@/features/realtime/typingStore';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { errorMessage } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import type { ChatThemeId, DmMessage, ReplyPreview } from '@/types/messages';
import { cn, displayName } from '@/utils/format';
import { cacheMessage, dmApi, dmKeys, useConversation, useDeleteMessage, useMarkRead, useMessages, useRoom, useSetTheme, useToggleFavorite } from '../api';
import { formatDuration, previewOf, replySenderName, tickState } from '../chat';
import { flattenPages, groupMessages } from '../helpers';
import { newestOwnMessageId, seenByCount, seenByLabel, typingLabel } from '../rooms';
import { getTheme, themeVars } from '../themes';
import type { VoiceRecording } from '../useVoiceRecorder';
import '../chatTheme.css';
import { ConnectionHint, OnlineDot, RoomAvatar } from './bits';
import { Composer, type ReplyDraft } from './Composer';
import { MessageBubble, Ticks, bubbleClasses, time } from './MessageBubble';
import { ReplyQuote } from './ReplyQuote';
import { RoomPanel } from './RoomPanel';
import { ThemePicker } from './ThemePicker';

interface Pending {
  tempId: string;
  body: string;
  files: File[];
  previews: string[];
  voice: VoiceRecording | null;
  replyTo: ReplyPreview | null;
  replySender: string | null;
  mediaIds: number[] | null;
  status: 'sending' | 'failed';
  error?: string;
}

const prefersReducedMotion = () => typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

const headerBtn = 'inline-flex h-9 w-9 items-center justify-center rounded-full text-muted transition-colors hover:bg-surface-2 hover:text-text';

export function Thread({ id }: { id: number }) {
  const qc = useQueryClient();
  const meId = useAuthStore((s) => s.user?.id);
  const conv = useConversation(id);
  const msgs = useMessages(id);
  const markRead = useMarkRead(id);
  const del = useDeleteMessage(id);
  const favorite = useToggleFavorite();
  const setTheme = useSetTheme(id);
  const typers = useTypingUsers(id);
  const [pending, setPending] = useState<Pending[]>([]);
  const [reply, setReply] = useState<ReplyDraft | null>(null);
  const [flashId, setFlashId] = useState<number | null>(null);
  const [themeOpen, setThemeOpen] = useState(false);
  const [showJump, setShowJump] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);
  const c = conv.data;
  const other = c?.other ?? null;
  const isRoom = c?.kind === 'ROOM';
  const room = useRoom(id, isRoom);
  const [panelOpen, setPanelOpen] = useState(false);
  const members = useMemo(() => room.data?.members ?? [], [room.data]);
  const memberById = useMemo(() => new Map(members.map((m) => [m.user.id, m.user])), [members]);
  const theme = getTheme(c?.theme);
  useDocumentTitle(isRoom && c?.room ? `${c.room.name} · Messages` : other ? `${displayName(other)} · Messages` : 'Messages');

  const messages = useMemo(() => flattenPages(msgs.data), [msgs.data]);
  const days = useMemo(() => groupMessages(messages), [messages]);
  const otherLastRead = isRoom ? null : c?.otherLastReadMessageId ?? null;
  // Pack rooms: one aggregated receipt under my newest message ("Seen by 3").
  const roomSeenId = isRoom ? newestOwnMessageId(messages, meId) : null;
  const roomSeen = roomSeenId == null ? null
    : seenByLabel(seenByCount(roomSeenId, meId ?? null, members, meId), Math.max(0, members.length - 1));
  const typingNames = typers.map((u) => memberById.get(u)?.username ?? 'Someone');
  const newest = messages[messages.length - 1];

  const nameOf = useCallback(
    (uid: number) => {
      if (!isRoom) return other && other.id === uid ? displayName(other) : undefined;
      const u = memberById.get(uid);
      return u ? displayName(u) : undefined;
    },
    [isRoom, other, memberById],
  );
  const senderName = (uid: number | null) => replySenderName(uid, meId, nameOf);

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
  }, [messages, pending.length, typers.length, reply]);
  useEffect(() => {
    atBottom.current = true;
    prevFirst.current = null;
  }, [id]);

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    setShowJump(!atBottom.current && el.scrollHeight - el.scrollTop - el.clientHeight > 400);
    prevHeight.current = el.scrollHeight;
    if (el.scrollTop < 120 && msgs.hasNextPage && !msgs.isFetchingNextPage) void msgs.fetchNextPage();
  };
  const scrollToLatest = () => {
    const el = scroller.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
  };

  /* ---------- jump to a quoted original */
  const flashTimer = useRef<number | null>(null);
  useEffect(() => () => {
    if (flashTimer.current != null) window.clearTimeout(flashTimer.current);
  }, []);
  const jumpTo = (messageId: number) => {
    const el = scroller.current?.querySelector<HTMLElement>(`#msg-${messageId}`);
    if (!el) {
      toast.info('The original message isn’t loaded. Scroll up to find it.');
      return;
    }
    el.scrollIntoView({ block: 'center', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
    setFlashId(null);
    window.requestAnimationFrame(() => setFlashId(messageId));
    if (flashTimer.current != null) window.clearTimeout(flashTimer.current);
    flashTimer.current = window.setTimeout(() => setFlashId(null), 1700);
    el.querySelector<HTMLElement>('button, a, [tabindex]')?.focus({ preventScroll: true });
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
          if (p.voice) mediaIds.push((await dmApi.uploadVoice(p.voice.blob, p.voice.durationMs, p.voice.mime)).id);
          else for (const f of p.files) mediaIds.push((await dmApi.uploadMedia(f)).id);
          const ids = mediaIds;
          setPending((ps) => ps.map((x) => (x.tempId === p.tempId ? { ...x, mediaIds: ids } : x)));
        }
        const msg = await dmApi.send(id, p.body, mediaIds, p.replyTo?.id);
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
  const queue = (body: string, files: File[], voice: VoiceRecording | null) => {
    const p: Pending = {
      tempId: `t${Date.now()}${Math.random().toString(36).slice(2, 6)}`,
      body,
      files,
      previews: files.map((f) => URL.createObjectURL(f)),
      voice,
      replyTo: reply?.preview ?? null,
      replySender: reply?.senderName ?? null,
      mediaIds: files.length || voice ? null : [],
      status: 'sending',
    };
    atBottom.current = true;
    setReply(null);
    setPending((ps) => [...ps, p]);
    void runSend(p);
  };
  const discard = (p: Pending) => {
    p.previews.forEach((u) => URL.revokeObjectURL(u));
    setPending((ps) => ps.filter((x) => x.tempId !== p.tempId));
  };

  const startReply = (m: DmMessage) => setReply({ preview: previewOf(m), senderName: senderName(m.senderId) });
  const onDelete = (messageId: number) =>
    del.mutate(messageId, {
      onSuccess: () => setReply((r) => (r?.preview.id === messageId ? null : r)),
      onError: (e) => toast.error(errorMessage(e)),
    });
  const pickTheme = (t: ChatThemeId) => {
    setThemeOpen(false);
    if (t === theme.id) return;
    setTheme.mutate(t, { onError: (e) => toast.error(errorMessage(e)) });
  };

  if (conv.isError) {
    return (
      <div className="flex h-full flex-col">
        <div className="border-b border-border px-3 py-2.5 md:hidden"><Link to="/messages" className="inline-flex items-center gap-1.5 text-sm text-muted"><ArrowLeft size={15} /> Messages</Link></div>
        <ErrorState error={conv.error} onRetry={() => conv.refetch()} title="Could not open this conversation" />
      </div>
    );
  }

  const otherName = isRoom ? c?.room?.name ?? 'Pack room' : other ? displayName(other) : 'Deleted account';
  const status = isRoom && c?.room
    ? typingLabel(typingNames) ?? `${c.room.memberCount} members${c.online ? ' · someone is online' : ''}`
    : typers.length ? 'typing…' : c?.online ? 'Online' : other ? `u/${other.username}` : '';
  const isFav = !!c?.favorite;

  return (
    <div className="chat-theme flex h-full min-h-0 flex-col" style={themeVars(theme)}>
      <header className="z-10 flex items-center gap-2 border-b border-border bg-surface/95 px-2 py-2 backdrop-blur sm:gap-3 sm:px-3">
        <Link to="/messages" aria-label="Back to conversations" className={cn(headerBtn, 'md:hidden')}>
          <ArrowLeft size={18} />
        </Link>
        {c ? (
          <>
            <span className="relative shrink-0">
              {isRoom && c.room ? <RoomAvatar icon={c.room.iconEmoji} /> : <Avatar src={other?.avatarUrl} name={other?.username ?? '?'} size="md" />}
              {c.online && <OnlineDot className="absolute -bottom-0.5 -right-0.5" />}
            </span>
            <div className="min-w-0 flex-1">
              {!isRoom && other ? (
                <Link to={`/u/${other.username}`} className="block truncate text-[15px] font-semibold leading-5 hover:underline">{otherName}</Link>
              ) : (
                <span className={cn('block truncate text-[15px] font-semibold leading-5', !isRoom && 'text-muted')}>{otherName}</span>
              )}
              <span className={cn('block truncate text-xs', typers.length ? 'chat-accent-text font-medium' : 'text-muted')} aria-live="polite">{status}</span>
            </div>
            <ConnectionHint className="hidden sm:inline-flex" />
            <button
              type="button"
              onClick={() => favorite.mutate({ id, favorite: !isFav }, { onError: (e) => toast.error(errorMessage(e)) })}
              aria-pressed={isFav}
              aria-label={isFav ? 'Remove from favourites' : 'Add to favourites'}
              title={isFav ? 'Remove from favourites' : 'Add to favourites'}
              className={cn(headerBtn, isFav && 'text-warning hover:text-warning')}
            >
              <Star size={18} fill={isFav ? 'currentColor' : 'none'} />
            </button>
            <button type="button" onClick={() => setThemeOpen(true)} aria-label="Chat theme" title="Chat theme" className={headerBtn}>
              <Palette size={18} />
            </button>
            {isRoom && (
              <>
                <button type="button" onClick={() => setPanelOpen(true)} aria-label="Members and room settings" title="Members" className={headerBtn}>
                  <Users size={18} />
                </button>
                <RoomPanel id={id} open={panelOpen} onOpenChange={setPanelOpen} />
              </>
            )}
            <ThemePicker open={themeOpen} onOpenChange={setThemeOpen} current={theme.id} onPick={pickTheme} />
          </>
        ) : (
          <div className="flex flex-1 items-center gap-3"><Skeleton className="h-9 w-9 rounded-full" /><Skeleton className="h-4 w-32" /></div>
        )}
      </header>

      <div className="chat-wallpaper relative flex min-h-0 flex-1 flex-col">
        <div
          ref={scroller}
          onScroll={onScroll}
          className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 py-2 sm:px-5"
          role="log"
          aria-label={`Conversation with ${otherName}`}
          aria-live="polite"
          aria-relevant="additions"
        >
          {msgs.isPending ? (
            <div className="space-y-3 pt-4" aria-busy="true">
              {Array.from({ length: 5 }, (_, i) => (
                <div key={i} className={cn('flex', i % 2 && 'justify-end')}><Skeleton className="h-9 w-48 rounded-2xl" /></div>
              ))}
            </div>
          ) : msgs.isError ? (
            <ErrorState error={msgs.error} onRetry={() => msgs.refetch()} />
          ) : (
            <>
              {msgs.isFetchingNextPage && <div className="flex justify-center py-2"><Spinner size={16} /></div>}
              {!msgs.hasNextPage && messages.length > 0 && (
                <p className="mx-auto my-3 w-fit max-w-[90%] rounded-xl bg-surface px-3 py-1.5 text-center text-xs text-muted shadow-sm">
                  {isRoom ? `This is the start of ${otherName}.` : `This is the start of your conversation with ${otherName}.`}
                </p>
              )}
              {messages.length === 0 && pending.length === 0 && (
                <p className="mx-auto mt-10 w-fit rounded-xl bg-surface px-4 py-2 text-center text-sm text-muted shadow-sm">No messages yet. Say hello.</p>
              )}
              {days.map((day) => (
                <section key={day.key} aria-label={day.label}>
                  <div className="sticky top-1 z-[5] my-3 flex justify-center">
                    <span className="rounded-lg bg-surface px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide text-muted shadow-sm">{day.label}</span>
                  </div>
                  {day.groups.map((g) => {
                    if (g.system) {
                      const s = g.messages[0];
                      return (
                        <div key={s.id} id={`msg-${s.id}`} className="my-2 flex justify-center px-6">
                          <p className="max-w-md rounded-lg bg-surface px-3 py-1 text-center text-xs text-muted shadow-sm" title={time(s.createdAt)}>{s.body}</p>
                        </div>
                      );
                    }
                    const mine = g.senderId === meId;
                    const blocked = g.messages[0].fromBlocked;
                    const sender = isRoom && !mine && !blocked ? memberById.get(g.senderId) : undefined;
                    const showSender = isRoom && !mine && !blocked;
                    const bubbles = g.messages.map((m, i) => (
                      <MessageBubble
                        key={m.id}
                        m={m}
                        mine={mine}
                        first={i === 0}
                        senderLabel={showSender && i === 0 ? (
                          <span className="chat-accent-text text-xs font-semibold">{sender ? displayName(sender) : 'Former member'}</span>
                        ) : undefined}
                        tick={mine && !isRoom && !m.deleted ? tickState(m.id, otherLastRead) : null}
                        replySender={m.replyTo ? senderName(m.replyTo.senderId) : null}
                        highlighted={flashId === m.id}
                        onReply={() => startReply(m)}
                        onDelete={() => onDelete(m.id)}
                        onJump={jumpTo}
                      />
                    ));
                    return (
                      <div key={g.messages[0].id} className="mb-2 space-y-0.5">
                        {isRoom && !mine ? (
                          <div className="flex items-end gap-1.5">
                            <span className="mb-1 shrink-0">
                              {sender ? <Avatar src={sender.avatarUrl} name={sender.username} size="sm" /> : <span className="block h-6 w-6" />}
                            </span>
                            <div className="min-w-0 flex-1 space-y-0.5">{bubbles}</div>
                          </div>
                        ) : bubbles}
                        {roomSeen && g.messages.some((m) => m.id === roomSeenId) && (
                          <p className="text-right">
                            <span className="rounded-full bg-surface px-2 py-0.5 text-[11px] text-muted shadow-sm">{roomSeen}</span>
                          </p>
                        )}
                      </div>
                    );
                  })}
                </section>
              ))}
              {pending.map((p) => (
                <div key={p.tempId} className="mb-2 flex flex-col items-end gap-1">
                  <div className={cn('max-w-[min(80%,36rem)]', p.status === 'sending' && 'opacity-75')}>
                    <div className={cn(bubbleClasses(true, true), (p.previews.length > 0 || p.voice) && 'px-1.5 pt-1.5')}>
                      {p.replyTo && <ReplyQuote preview={p.replyTo} senderName={p.replySender ?? 'Someone'} tone="out" className="mb-1" />}
                      {p.previews.length > 0 && (
                        <div className={cn('grid gap-1', p.previews.length > 1 && 'grid-cols-2')}>
                          {p.previews.map((u) => <img key={u} src={u} alt="Attachment preview" className="max-h-72 w-full rounded-xl object-cover" />)}
                        </div>
                      )}
                      {p.voice && (
                        <p className="flex items-center gap-2 px-2 py-2 text-sm">
                          <Mic size={16} aria-hidden="true" /> Voice message · {formatDuration(p.voice.durationMs)}
                        </p>
                      )}
                      {p.body && <p className={cn('whitespace-pre-wrap break-words', (p.previews.length > 0 || p.voice) && 'px-1 pt-1')}>{p.body}</p>}
                      {p.status === 'sending' && (
                        <span className="float-right ml-2 inline-flex items-center gap-1 text-[11px] opacity-80"><Ticks state="sending" /></span>
                      )}
                      <span className="block clear-both" />
                    </div>
                  </div>
                  {p.status === 'sending' ? (
                    <span className="sr-only">Sending…</span>
                  ) : (
                    <span className="flex items-center gap-2 rounded-full bg-surface px-2.5 py-0.5 text-[11px] text-danger shadow-sm" role="alert">
                      Not sent{p.error ? `: ${p.error}` : ''}
                      <button type="button" onClick={() => void runSend(p)} className="inline-flex items-center gap-0.5 font-semibold underline"><RotateCcw size={11} /> Retry</button>
                      <button type="button" onClick={() => discard(p)} className="font-semibold text-muted underline">Discard</button>
                    </span>
                  )}
                </div>
              ))}
              {typers.length > 0 && (
                <div className="mb-2 flex" aria-live="polite">
                  <span className="chat-bubble-in inline-flex items-center gap-2 rounded-2xl rounded-tl-[4px] px-3 py-2 text-xs">
                    <span className="flex gap-1" aria-hidden="true">
                      {[0, 1, 2].map((i) => (
                        <span key={i} className="h-1.5 w-1.5 animate-bounce rounded-full bg-current opacity-60 motion-reduce:animate-none" style={{ animationDelay: `${i * 150}ms` }} />
                      ))}
                    </span>
                    {isRoom ? typingLabel(typingNames) : `${otherName} is typing…`}
                  </span>
                </div>
              )}
            </>
          )}
        </div>

        {showJump && (
          <button
            type="button"
            onClick={scrollToLatest}
            aria-label="Scroll to latest messages"
            className="absolute bottom-20 right-4 z-10 inline-flex h-10 w-10 items-center justify-center rounded-full bg-surface text-muted shadow-float transition-colors hover:text-text"
          >
            <ChevronDown size={20} />
          </button>
        )}

        {c && (
          <Composer
            conversationId={id}
            disabledReason={c.canMessage ? null : c.cannotMessageReason ?? 'You can’t message this person.'}
            onSend={(body, files) => queue(body, files, null)}
            onSendVoice={(v) => queue('', [], v)}
            reply={reply}
            onCancelReply={() => setReply(null)}
          />
        )}
      </div>
    </div>
  );
}

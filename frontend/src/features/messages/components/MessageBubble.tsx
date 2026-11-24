import { useQueryClient } from '@tanstack/react-query';
import { Ban, Check, CheckCheck, Clock3, Copy, MoreHorizontal, Reply, Trash2 } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode, type TouchEvent } from 'react';
import { Menu, MenuContent, MenuItem, MenuTrigger } from '@/components/ui/Menu';
import { toast } from '@/components/ui/Toast';
import { AddReactionButton, ReactionBar } from '@/features/reactions/components/ReactionBar';
import type { DmMedia, DmMessage } from '@/types/messages';
import { responsiveImage } from '@/utils/images';
import { cn } from '@/utils/format';
import { dmKeys } from '../api';
import { canReply, mediaKindOf, swipeOffset, swipeTriggers, type TickState } from '../chat';
import { ReplyQuote } from './ReplyQuote';
import { VoiceNote } from './VoiceNote';

export const time = (iso: string) => new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });

/**
 * A DM image. Signed URLs expire (30 to 60 min), so a failed load refetches the thread once for fresh URLs instead of
 * leaving a broken image behind.
 */
function DmImage({ img, conversationId }: { img: DmMedia; conversationId: number }) {
  const qc = useQueryClient();
  const retried = useRef(false);
  const r = responsiveImage(img);
  return (
    <a href={img.url} target="_blank" rel="noreferrer" className="block overflow-hidden rounded-xl bg-black/10">
      <img
        src={r.src}
        srcSet={r.srcSet}
        sizes="(min-width: 768px) 320px, 70vw"
        alt="Attachment"
        loading="lazy"
        decoding="async"
        width={img.width ?? undefined}
        height={img.height ?? undefined}
        className="max-h-72 w-full object-cover"
        onError={() => {
          if (!img.signed || retried.current) return;
          retried.current = true;
          void qc.invalidateQueries({ queryKey: dmKeys.messages(conversationId) });
        }}
      />
    </a>
  );
}

export function Ticks({ state }: { state: TickState }) {
  if (state === 'sending') return <Clock3 size={13} aria-label="Sending" role="img" />;
  if (state === 'read') return <CheckCheck size={15} strokeWidth={2.4} aria-label="Read" role="img" />;
  return <Check size={14} aria-label="Sent" role="img" className="opacity-80" />;
}

/** Time (and ticks) floated into the last line of the bubble, WhatsApp style. */
function Meta({ iso, tick }: { iso: string; tick?: TickState | null }) {
  return (
    <span className="relative top-1.5 float-right ml-2.5 inline-flex select-none items-center gap-1 text-[11px] leading-4 opacity-80">
      <time dateTime={iso}>{time(iso)}</time>
      {tick && <Ticks state={tick} />}
    </span>
  );
}

/** Soft, round bubbles; later bubbles in a run tuck their top corner in on the sender's side (Instagram style). */
export function bubbleClasses(mine: boolean, first: boolean) {
  return cn(
    'relative rounded-[20px] px-3 py-1.5 text-[15px] leading-snug',
    mine ? 'chat-bubble-out' : 'chat-bubble-in',
    !first && (mine ? 'rounded-tr-md' : 'rounded-tl-md'),
  );
}

interface Props {
  m: DmMessage;
  mine: boolean;
  first: boolean;
  /** Rooms: the sender's name on the first bubble of someone else's run. */
  senderLabel?: ReactNode;
  tick: TickState | null;
  replySender: string | null;
  highlighted: boolean;
  onReply: () => void;
  onDelete: () => void;
  onJump: (id: number) => void;
}

export function MessageBubble({ m, mine, first, senderLabel, tick, replySender, highlighted, onReply, onDelete, onJump }: Props) {
  const [dx, setDx] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const touch = useRef<{ x: number; y: number; active: boolean } | null>(null);
  const press = useRef<number | null>(null);
  const replyable = canReply(m) && !m.deleted;
  const actionable = !m.deleted && !m.fromBlocked;

  const clearPress = () => {
    if (press.current != null) window.clearTimeout(press.current);
    press.current = null;
  };
  useEffect(() => clearPress, []);
  // Touch: swipe right to reply, long-press for the message menu.
  const onTouchStart = (e: TouchEvent) => {
    if (e.touches.length !== 1) return;
    touch.current = { x: e.touches[0].clientX, y: e.touches[0].clientY, active: false };
    if (actionable) press.current = window.setTimeout(() => setMenuOpen(true), 480);
  };
  const onTouchMove = (e: TouchEvent) => {
    const t = touch.current;
    if (!t) return;
    const mx = e.touches[0].clientX - t.x;
    const my = e.touches[0].clientY - t.y;
    if (Math.abs(mx) > 8 || Math.abs(my) > 8) clearPress();
    if (!replyable) return;
    const off = swipeOffset(mx, my);
    if (off == null) {
      if (!t.active) touch.current = null; // vertical scroll: let it go
      return;
    }
    t.active = true;
    setDx(off);
  };
  const onTouchEnd = () => {
    clearPress();
    if (touch.current?.active && swipeTriggers(dx)) onReply();
    touch.current = null;
    setDx(0);
  };

  const images = m.media.filter((x) => mediaKindOf(x) === 'IMAGE');
  const voice = m.media.find((x) => mediaKindOf(x) === 'AUDIO');
  const tone = mine ? 'out' : 'in';

  let content: ReactNode;
  if (m.deleted || m.fromBlocked) {
    content = (
      <div className={cn(bubbleClasses(mine, first), 'opacity-80')}>
        <p className="italic">
          <Ban size={13} className="mr-1 inline -translate-y-px" aria-hidden="true" />
          {m.deleted ? 'Message deleted' : 'Message from someone you blocked'}
          <Meta iso={m.createdAt} />
        </p>
      </div>
    );
  } else {
    content = (
      <div className={cn(bubbleClasses(mine, first), (images.length > 0 || voice) && 'px-1.5 pt-1.5')}>
        {senderLabel && <div className="px-1 pb-0.5">{senderLabel}</div>}
        {m.replyTo && (
          <ReplyQuote
            preview={m.replyTo}
            senderName={replySender ?? 'Someone'}
            tone={tone}
            onJump={() => onJump(m.replyTo!.id)}
            className="mb-1"
          />
        )}
        {images.length > 0 && (
          <div className={cn('grid gap-1', images.length > 1 && 'grid-cols-2')}>
            {images.map((img) => <DmImage key={img.id} img={img} conversationId={m.conversationId} />)}
          </div>
        )}
        {voice && (
          <div className="px-1">
            <VoiceNote media={voice} conversationId={m.conversationId} tone={tone} />
          </div>
        )}
        {m.body ? (
          <p className={cn('whitespace-pre-wrap break-words', (images.length > 0 || voice) && 'px-1 pt-1')}>
            {m.body}
            <Meta iso={m.createdAt} tick={tick} />
          </p>
        ) : (
          <p className="clear-both px-1 pb-0.5 text-right">
            <Meta iso={m.createdAt} tick={tick} />
            <span className="block h-1.5" />
          </p>
        )}
        <span className="block clear-both" />
      </div>
    );
  }

  const copy = () => {
    void navigator.clipboard?.writeText(m.body).then(
      () => toast.success('Copied'),
      () => toast.error('Couldn’t copy that message.'),
    );
  };

  return (
    <div
      id={`msg-${m.id}`}
      className={cn('group relative -mx-2 flex items-center gap-1 px-2 py-px [@media(hover:none)]:select-none', mine && 'flex-row-reverse', highlighted && 'chat-flash')}
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
      onTouchCancel={onTouchEnd}
      style={{ touchAction: 'pan-y' }}
    >
      {dx > 0 && (
        <span
          aria-hidden="true"
          className={cn('absolute left-1 top-1/2 inline-flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full bg-surface text-muted shadow', swipeTriggers(dx) && 'chat-accent')}
          style={{ opacity: Math.min(1, dx / 56) }}
        >
          <Reply size={16} />
        </span>
      )}
      <div
        className={cn('flex min-w-0 max-w-[min(80%,36rem)] flex-col', mine ? 'items-end' : 'items-start', dx === 0 && 'transition-transform duration-200')}
        style={dx ? { transform: `translateX(${dx}px)` } : undefined}
        title={new Date(m.createdAt).toLocaleString()}
      >
        {content}
        {actionable && <ReactionBar type="MESSAGE" id={m.id} compact align={mine ? 'end' : 'start'} withPicker={false} />}
      </div>
      {actionable && (
        <div className={cn('flex shrink-0 items-center opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100 has-[[aria-expanded=true]]:opacity-100', mine && 'flex-row-reverse')}>
          {replyable && (
            <button
              type="button"
              onClick={onReply}
              aria-label="Reply"
              title="Reply"
              className="rounded-full p-1.5 text-faint hover:bg-surface hover:text-text"
            >
              <Reply size={15} />
            </button>
          )}
          <AddReactionButton type="MESSAGE" id={m.id} compact popupAlign={mine ? 'end' : 'start'} />
          <Menu open={menuOpen} onOpenChange={setMenuOpen}>
            <MenuTrigger asChild>
              <button type="button" aria-label="Message options" className="rounded-full p-1.5 text-faint hover:bg-surface hover:text-text">
                <MoreHorizontal size={15} />
              </button>
            </MenuTrigger>
            <MenuContent align={mine ? 'end' : 'start'}>
              {replyable && <MenuItem icon={<Reply />} onSelect={onReply}>Reply</MenuItem>}
              {m.body && <MenuItem icon={<Copy />} onSelect={copy}>Copy text</MenuItem>}
              {mine && <MenuItem danger icon={<Trash2 />} onSelect={onDelete}>Delete for everyone</MenuItem>}
            </MenuContent>
          </Menu>
        </div>
      )}
    </div>
  );
}

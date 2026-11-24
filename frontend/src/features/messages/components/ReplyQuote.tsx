import { Ban, Camera, Mic } from 'lucide-react';
import type { ReplyPreview } from '@/types/messages';
import { cn } from '@/utils/format';
import { replyExcerpt, replyMediaLabel } from '../chat';

/**
 * The quoted original inside a reply bubble (and above the composer). Inside a bubble it is a button that jumps to the
 * original message.
 */
export function ReplyQuote({
  preview,
  senderName,
  tone,
  onJump,
  className,
}: {
  preview: ReplyPreview;
  senderName: string;
  tone: 'out' | 'in' | 'composer';
  onJump?: () => void;
  className?: string;
}) {
  const media = replyMediaLabel(preview);
  const text = replyExcerpt(preview);
  const Icon = preview.deleted ? Ban : preview.mediaKind === 'AUDIO' ? Mic : preview.mediaKind === 'IMAGE' ? Camera : null;
  const body = (
    <>
      <span className={cn('block truncate text-xs font-semibold', tone === 'out' ? '' : 'chat-accent-text')}>{senderName}</span>
      <span className={cn('flex min-w-0 items-center gap-1 text-xs', preview.deleted && 'italic')}>
        {Icon && <Icon size={12} aria-hidden="true" className="shrink-0" />}
        <span className="line-clamp-2 break-words">{text}</span>
        {media && text !== media && <span className="sr-only">({media})</span>}
      </span>
    </>
  );
  const cls = cn(
    'block w-full min-w-0 rounded-lg border-l-4 px-2.5 py-1.5 text-left',
    tone === 'out' && 'border-current bg-black/15',
    tone === 'in' && 'border-[color:var(--ct-accent)] bg-black/5 dark:bg-white/5',
    tone === 'composer' && 'border-[color:var(--ct-accent)] bg-surface-2 text-text',
    className,
  );
  if (!onJump) return <div className={cls}>{body}</div>;
  return (
    <button type="button" onClick={onJump} className={cn(cls, 'transition-opacity hover:opacity-90')} aria-label={`Replying to ${senderName}: ${text}. Show original message`}>
      {body}
    </button>
  );
}

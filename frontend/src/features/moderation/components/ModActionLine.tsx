import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/Display';
import type { ModAction } from '@/types/moderation';
import { timeAgo } from '@/utils/format';
import { ACTION_LABELS, formatBanExpiry, isAutomodAction } from '../helpers';

/** One moderation log entry: who did what to whom, why, and whether it was later reversed. */
export function ModActionLine({ a, slug }: { a: ModAction; slug: string }) {
  const href = a.postId ? `/c/${slug}/p/${a.postId}${a.commentId ? `#c-${a.commentId}` : ''}` : null;
  return (
    <div className="text-sm">
      <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
        <span className="font-semibold">{ACTION_LABELS[a.action]}</span>
        {a.targetUser && <Link to={`/u/${a.targetUser.username}`} className="text-muted hover:underline">u/{a.targetUser.username}</Link>}
        {a.reversed && <Badge tone="info" className="rounded-full px-2">Reversed</Badge>}
        <span className="ml-auto text-xs text-faint">{timeAgo(a.createdAt)}</span>
      </div>
      {a.targetTitle && (href ? <Link to={href} className="mt-0.5 line-clamp-1 block text-muted hover:underline">{a.targetTitle}</Link> : <p className="mt-0.5 line-clamp-1 text-muted">{a.targetTitle}</p>)}
      <p className="mt-0.5 text-xs text-faint">
        {a.moderator ? `by u/${a.moderator.username}` : isAutomodAction(a) ? 'by automod' : 'by a former moderator'}
        {a.reason ? ` · “${a.reason}”` : ''}
        {a.action === 'BAN' ? ` · ${formatBanExpiry(a.expiresAt)}` : ''}
      </p>
    </div>
  );
}

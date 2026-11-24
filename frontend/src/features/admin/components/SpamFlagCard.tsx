import { CheckCircle2, MessageSquare, ShieldAlert, ShieldX } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge } from '@/components/ui/Display';
import type { SpamFlag } from '@/types/admin';
import { timeAgo } from '@/utils/format';
import { accountAgeDays, ruleLabel } from '../helpers';

/**
 * One spam flag. Open holds get Approve (release) and Remove; rejections and resolved flags are read-only records.
 * Used by the community mod queue ("Held" tab) and the admin Flags page.
 */
export function SpamFlagCard({
  flag,
  showCommunity = false,
  busy,
  onResolve,
}: {
  flag: SpamFlag;
  showCommunity?: boolean;
  busy?: boolean;
  onResolve?: (verdict: 'approve' | 'remove') => void;
}) {
  const isComment = flag.targetType === 'COMMENT';
  const href = flag.postId ? `/c/${flag.community.slug}/p/${flag.postId}${isComment && flag.commentId ? `#c-${flag.commentId}` : ''}` : null;
  const age = accountAgeDays(flag.authorJoinedAt, flag.createdAt);
  const open = flag.status === 'OPEN' && flag.outcome === 'HELD';
  return (
    <article className="rounded-2xl border border-border p-4" aria-label={`Spam flag on a ${isComment ? 'comment' : 'post'}`}>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
        <Badge tone={flag.outcome === 'REJECTED' ? 'danger' : 'warning'} className="rounded-full px-2">
          <ShieldAlert size={11} /> {flag.outcome === 'REJECTED' ? 'Rejected' : 'Held for review'}
        </Badge>
        <Badge className="rounded-full px-2">{isComment ? <><MessageSquare size={11} /> Comment</> : 'Post'}</Badge>
        {flag.status !== 'OPEN' && flag.outcome === 'HELD' && (
          <Badge tone={flag.status === 'APPROVED' ? 'accent' : 'neutral'} className="rounded-full px-2">{flag.status.toLowerCase()}</Badge>
        )}
        {showCommunity && <Link to={`/c/${flag.community.slug}`} className="font-semibold text-text hover:underline">c/{flag.community.slug}</Link>}
        <span className="ml-auto">{timeAgo(flag.createdAt)}</span>
      </div>
      {flag.postTitle && (href ? (
        <Link to={href} className="mt-2 block font-semibold hover:underline">{flag.postTitle}</Link>
      ) : (
        <p className="mt-2 font-semibold">{flag.postTitle}</p>
      ))}
      {flag.excerpt && <p className="mt-1 line-clamp-3 whitespace-pre-wrap text-sm text-muted">{flag.excerpt}</p>}
      <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-faint">
        {flag.author ? (
          <>
            <Avatar src={flag.author.avatarUrl} name={flag.author.username} size="xs" />
            <Link to={`/u/${flag.author.username}`} className="hover:underline">u/{flag.author.username}</Link>
          </>
        ) : (
          <span className="italic">[deleted]</span>
        )}
        {age != null && <span>· account {age === 0 ? 'less than a day' : `${age} ${age === 1 ? 'day' : 'days'}`} old</span>}
      </div>
      <div className="mt-3 flex flex-wrap gap-1.5">
        {flag.rules.map((r) => (
          <span key={r} className="rounded-full bg-warning/10 px-2.5 py-1 text-xs font-semibold text-warning">{ruleLabel(r)}</span>
        ))}
      </div>
      <p className="mt-2 text-xs text-muted">{flag.details}</p>
      {flag.resolvedBy && flag.resolvedAt && (
        <p className="mt-1 text-xs text-faint">Resolved by u/{flag.resolvedBy.username} {timeAgo(flag.resolvedAt)}</p>
      )}
      {open && onResolve && (
        <div className="mt-3 flex flex-wrap gap-2">
          <Button size="sm" loading={busy} onClick={() => onResolve('approve')}><CheckCircle2 size={14} /> Approve and publish</Button>
          <Button size="sm" variant="danger" loading={busy} onClick={() => onResolve('remove')}><ShieldX size={14} /> Remove</Button>
        </div>
      )}
    </article>
  );
}

import { Ban, CheckCircle2, Flag, Lock, MessageSquare, ShieldX } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { QueueItem } from '@/types/moderation';
import { timeAgo } from '@/utils/format';
import { modApi, useModMutation, useModQueue } from '../api';
import { BanDialog } from './BanDialog';
import { ReasonDialog } from './ReasonDialog';

function QueueCard({ slug, item }: { slug: string; item: QueueItem }) {
  const [removing, setRemoving] = useState(false);
  const [banning, setBanning] = useState(false);
  const isComment = item.targetType === 'COMMENT';
  const act = useModMutation<{ verb: string; reason?: string }, unknown>(slug, ({ verb, reason }) =>
    isComment ? modApi.comment(item.commentId!, verb as 'remove' | 'approve', reason) : modApi.post(item.postId, verb as 'remove' | 'approve' | 'lock', reason),
  );
  const run = (verb: string, done: string, reason?: string) =>
    act.mutate({ verb, reason }, { onSuccess: () => { toast.success(done); setRemoving(false); }, onError: (e) => toast.error(errorMessage(e)) });
  const href = `/c/${slug}/p/${item.postId}${isComment ? `#c-${item.commentId}` : ''}`;

  return (
    <article className="rounded-2xl border border-border p-4" aria-label={item.title}>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
        <Badge tone="danger" className="rounded-full px-2"><Flag size={11} /> {item.reportCount} {item.reportCount === 1 ? 'report' : 'reports'}</Badge>
        <Badge className="rounded-full px-2">{isComment ? <><MessageSquare size={11} /> Comment</> : 'Post'}</Badge>
        {item.removed && <Badge tone="warning" className="rounded-full px-2">Removed</Badge>}
        {item.locked && <Badge className="rounded-full px-2"><Lock size={11} /> Locked</Badge>}
        {item.pinned && <Badge tone="accent" className="rounded-full px-2">Pinned</Badge>}
        <span className="ml-auto">last reported {timeAgo(item.lastReportedAt)}</span>
      </div>
      <Link to={href} className="mt-2 block font-semibold hover:underline">{item.title}</Link>
      {item.excerpt && <p className="mt-1 line-clamp-3 text-sm text-muted">{item.excerpt}</p>}
      <div className="mt-2 flex items-center gap-1.5 text-xs text-faint">
        {item.author ? (
          <>
            <Avatar src={item.author.avatarUrl} name={item.author.username} size="xs" />
            <Link to={`/u/${item.author.username}`} className="hover:underline">u/{item.author.username}</Link>
          </>
        ) : (
          <span className="italic">[deleted]</span>
        )}
        <span>· posted {timeAgo(item.createdAt)}</span>
      </div>
      <div className="mt-3 flex flex-wrap gap-1.5">
        {item.reasons.map((r) => (
          <span key={r.reason} className="rounded-full bg-danger/10 px-2.5 py-1 text-xs font-semibold text-danger">
            {r.reason}{r.count > 1 ? ` ×${r.count}` : ''}
          </span>
        ))}
      </div>
      {item.details.length > 0 && (
        <ul className="mt-2 space-y-1">
          {item.details.map((d, i) => <li key={i} className="border-l-2 border-border pl-2 text-xs italic text-muted">“{d}”</li>)}
        </ul>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        {!item.removed && <Button size="sm" variant="danger" onClick={() => setRemoving(true)}><ShieldX size={14} /> Remove</Button>}
        <Button size="sm" variant="secondary" loading={act.isPending} onClick={() => run('approve', item.removed ? 'Restored and reports cleared' : 'Reports dismissed')}>
          <CheckCircle2 size={14} /> {item.removed ? 'Restore' : 'Approve'}
        </Button>
        {!isComment && !item.locked && <Button size="sm" variant="secondary" onClick={() => run('lock', 'Comments locked')}><Lock size={14} /> Lock</Button>}
        {item.author && <Button size="sm" variant="ghost" onClick={() => setBanning(true)}><Ban size={14} /> Ban author</Button>}
      </div>
      <ReasonDialog open={removing} onOpenChange={setRemoving} title={`Remove this ${isComment ? 'comment' : 'post'}?`} confirmLabel="Remove" loading={act.isPending} onConfirm={(reason) => run('remove', 'Removed', reason || item.reasons[0]?.reason)} />
      {banning && item.author && <BanDialog slug={slug} username={item.author.username} open={banning} onOpenChange={setBanning} />}
    </article>
  );
}

export function QueueTab({ slug }: { slug: string }) {
  const q = useModQueue(slug);
  if (q.isPending) return <div className="space-y-3">{Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-40 rounded-2xl" />)}</div>;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data.length) return <EmptyState icon={CheckCircle2} title="The queue is clear" description="Nice work, pack leader. New reports from members will appear here." mascot="howl" />;
  return <div className="space-y-3">{q.data.map((i) => <QueueCard key={`${i.targetType}-${i.commentId ?? i.postId}`} slug={slug} item={i} />)}</div>;
}

import { CheckCircle2, Flag, ShieldCheck, ShieldX } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { Segmented } from '@/components/ui/Tabs';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { modApi } from '@/features/moderation/api';
import { errorMessage } from '@/services/apiError';
import type { AdminReport } from '@/types/admin';
import { timeAgo } from '@/utils/format';
import { adminApi, useAdminFlags, useAdminMutation, useAdminReports } from '../api';
import { SpamFlagCard } from './SpamFlagCard';

function SpamFlags() {
  const [status, setStatus] = useState<'OPEN' | 'ALL'>('OPEN');
  const q = useAdminFlags(status);
  const resolve = useAdminMutation(({ id, verdict }: { id: number; verdict: 'approve' | 'remove' }) => adminApi.resolveFlag(id, verdict));
  const items = q.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <div className="space-y-3">
      <Segmented<'OPEN' | 'ALL'>
        label="Spam flag filter"
        value={status}
        onChange={setStatus}
        options={[{ value: 'OPEN', label: 'Held for review' }, { value: 'ALL', label: 'All, including rejected' }]}
      />
      {q.isPending && <Skeleton className="h-40 rounded-2xl" />}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.isSuccess && items.length === 0 && (
        <EmptyState icon={ShieldCheck} title={status === 'OPEN' ? 'Nothing held for review' : 'No spam flags yet'} description="The write-time spam checks record what they hold or reject here." />
      )}
      {items.map((f) => (
        <SpamFlagCard
          key={f.id}
          flag={f}
          showCommunity
          busy={resolve.isPending && resolve.variables?.id === f.id}
          onResolve={(verdict) =>
            resolve.mutate({ id: f.id, verdict }, { onSuccess: () => toast.success(verdict === 'approve' ? 'Approved and published' : 'Removed'), onError: (e) => toast.error(errorMessage(e)) })
          }
        />
      ))}
      <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
    </div>
  );
}

function ReportCard({ r }: { r: AdminReport }) {
  const isComment = r.targetType === 'COMMENT';
  const act = useAdminMutation(async (verb: 'remove' | 'approve'): Promise<void> => {
    const reason = verb === 'remove' ? r.reasons[0] : undefined;
    if (isComment) await modApi.comment(r.commentId!, verb, reason);
    else await modApi.post(r.postId, verb, reason);
  });
  const run = (verb: 'remove' | 'approve') =>
    act.mutate(verb, { onSuccess: () => toast.success(verb === 'remove' ? 'Removed' : 'Reports dismissed'), onError: (e) => toast.error(errorMessage(e)) });
  return (
    <article className="rounded-2xl border border-border p-4">
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
        <Badge tone="danger" className="rounded-full px-2"><Flag size={11} /> {r.reportCount} {r.reportCount === 1 ? 'report' : 'reports'}</Badge>
        <Badge className="rounded-full px-2">{isComment ? 'Comment' : 'Post'}</Badge>
        {r.removed && <Badge tone="warning" className="rounded-full px-2">Removed</Badge>}
        <Link to={`/c/${r.community.slug}`} className="font-semibold text-text hover:underline">c/{r.community.slug}</Link>
        <span className="ml-auto">last {timeAgo(r.lastReportedAt)}</span>
      </div>
      <Link to={`/c/${r.community.slug}/p/${r.postId}${isComment ? `#c-${r.commentId}` : ''}`} className="mt-2 block font-semibold hover:underline">{r.title}</Link>
      {r.excerpt && <p className="mt-1 line-clamp-3 text-sm text-muted">{r.excerpt}</p>}
      <div className="mt-2 flex items-center gap-1.5 text-xs text-faint">
        {r.author ? <><Avatar src={r.author.avatarUrl} name={r.author.username} size="xs" /> u/{r.author.username}</> : <span className="italic">[deleted]</span>}
      </div>
      <div className="mt-2 flex flex-wrap gap-1.5">
        {r.reasons.map((x) => <span key={x} className="rounded-full bg-danger/10 px-2.5 py-1 text-xs font-semibold text-danger">{x}</span>)}
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {!r.removed && <Button size="sm" variant="danger" loading={act.isPending} onClick={() => run('remove')}><ShieldX size={14} /> Remove</Button>}
        <Button size="sm" variant="secondary" loading={act.isPending} onClick={() => run('approve')}><CheckCircle2 size={14} /> {r.removed ? 'Restore' : 'Dismiss reports'}</Button>
      </div>
    </article>
  );
}

function Reports() {
  const q = useAdminReports();
  if (q.isPending) return <Skeleton className="h-40 rounded-2xl" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  if (!q.data.length) return <EmptyState icon={CheckCircle2} title="No open reports" description="Reports from members of every community appear here, most-reported first." mascot="howl" />;
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted">Admins act with moderator rights in every community; actions are logged in that community’s mod log.</p>
      {q.data.map((r) => <ReportCard key={`${r.targetType}-${r.commentId ?? r.postId}`} r={r} />)}
    </div>
  );
}

/** Site-wide queues: content held by the spam checks, and member reports across communities. */
export function FlagsTab() {
  const [view, setView] = useState<'spam' | 'reports'>('spam');
  return (
    <div className="space-y-3">
      <Segmented<'spam' | 'reports'>
        label="Flag queues"
        value={view}
        onChange={setView}
        options={[{ value: 'spam', label: 'Spam checks' }, { value: 'reports', label: 'Reports' }]}
      />
      {view === 'spam' ? <SpamFlags /> : <Reports />}
    </div>
  );
}

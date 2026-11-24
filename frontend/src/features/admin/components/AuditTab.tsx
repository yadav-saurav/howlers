import { ScrollText } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { timeAgo } from '@/utils/format';
import { useAdminAudit } from '../api';
import { describeAudit } from '../helpers';

/** The append-only admin audit log: every suspension, role change, community status change and flag decision. */
export function AuditTab() {
  const q = useAdminAudit();
  if (q.isPending) return <Skeleton className="h-60 rounded-2xl" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const items = q.data.pages.flatMap((p) => p.items);
  if (!items.length) return <EmptyState icon={ScrollText} title="No admin actions yet" description="Every admin action is recorded here and can't be edited or deleted." />;
  return (
    <>
      <ul className="divide-y divide-border rounded-2xl border border-border">
        {items.map((a) => (
          <li key={a.id} className="px-4 py-3 text-sm">
            <p>
              {a.admin ? <Link to={`/u/${a.admin.username}`} className="font-semibold hover:underline">u/{a.admin.username}</Link> : <span className="italic text-faint">[deleted]</span>}{' '}
              {describeAudit(a)}
              {a.flagId != null && <span className="text-faint"> (flag #{a.flagId})</span>}
            </p>
            <p className="text-xs text-muted">
              <time dateTime={a.createdAt} title={new Date(a.createdAt).toLocaleString()}>{timeAgo(a.createdAt)}</time>
              {a.reason && <> · “{a.reason}”</>}
            </p>
          </li>
        ))}
      </ul>
      <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
    </>
  );
}

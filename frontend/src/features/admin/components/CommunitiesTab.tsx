import { Search, UsersRound } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { Input, Select } from '@/components/ui/Field';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { errorMessage } from '@/services/apiError';
import type { AdminCommunity } from '@/types/admin';
import type { CommunityStatus } from '@/types/api';
import { compactNumber, timeAgo } from '@/utils/format';
import { adminApi, useAdminCommunities, useAdminMutation } from '../api';
import { COMMUNITY_STATUS_COPY, nextCommunityStatuses } from '../helpers';
import { ActionDialog } from './ActionDialog';

const STATUS_ACTION: Record<CommunityStatus, { label: string; title: string; description: string }> = {
  QUARANTINED: {
    label: 'Quarantine',
    title: 'Quarantine this community?',
    description: 'It leaves Popular, All, trending and site search, and shows a notice to visitors. Members can still use it. Its moderators are told the reason.',
  },
  BANNED: {
    label: 'Ban',
    title: 'Ban this community?',
    description: 'Nobody but admins can open it or its posts, and its posts leave every feed. Its moderators are told the reason.',
  },
  ACTIVE: { label: 'Restore', title: 'Restore this community?', description: 'It returns to feeds and search as normal.' },
};

function Row({ c }: { c: AdminCommunity }) {
  const [target, setTarget] = useState<CommunityStatus | null>(null);
  const act = useAdminMutation(({ status, reason }: { status: CommunityStatus; reason: string }) => adminApi.setCommunityStatus(c.slug, status, reason || undefined));
  const s = COMMUNITY_STATUS_COPY[c.status];
  return (
    <li className="flex flex-wrap items-center gap-3 px-4 py-3">
      <Avatar src={c.iconUrl} name={c.slug} size="sm" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5 text-sm">
          <Link to={`/c/${c.slug}`} className="font-semibold hover:underline">c/{c.slug}</Link>
          {c.status !== 'ACTIVE' && <Badge tone={s.tone} className="rounded-full px-2">{s.label}</Badge>}
          {c.visibility !== 'PUBLIC' && <Badge className="rounded-full px-2">{c.visibility.toLowerCase()}</Badge>}
        </div>
        <p className="truncate text-xs text-muted">
          {compactNumber(c.memberCount)} members · {compactNumber(c.postCount)} posts · created {timeAgo(c.createdAt)}
          {c.owner && <> · owner u/{c.owner.username}</>}
        </p>
        {(c.openReports > 0 || c.openFlags > 0) && (
          <p className="text-xs text-warning">{c.openReports} open reports · {c.openFlags} held items</p>
        )}
        {c.statusReason && <p className="text-xs text-faint">Reason: “{c.statusReason}”</p>}
      </div>
      <div className="flex flex-wrap gap-1.5">
        {nextCommunityStatuses(c.status).map((st) => (
          <Button key={st} size="sm" variant={st === 'ACTIVE' ? 'secondary' : st === 'BANNED' ? 'danger' : 'outline'} onClick={() => setTarget(st)}>
            {STATUS_ACTION[st].label}
          </Button>
        ))}
      </div>
      {target && (
        <ActionDialog
          open
          onOpenChange={(o) => !o && setTarget(null)}
          title={STATUS_ACTION[target].title}
          description={STATUS_ACTION[target].description}
          confirmLabel={STATUS_ACTION[target].label}
          variant={target === 'BANNED' ? 'danger' : 'primary'}
          reasonRequired={target !== 'ACTIVE'}
          loading={act.isPending}
          onConfirm={(reason) =>
            act.mutate(
              { status: target, reason },
              { onSuccess: () => { toast.success(`c/${c.slug} is now ${COMMUNITY_STATUS_COPY[target].label.toLowerCase()}`); setTarget(null); }, onError: (e) => toast.error(errorMessage(e)) },
            )
          }
        />
      )}
    </li>
  );
}

export function CommunitiesTab() {
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const debounced = useDebouncedValue(q.trim(), 300);
  const list = useAdminCommunities(debounced, status);
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-2 sm:flex-row">
        <div className="relative flex-1">
          <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" aria-hidden="true" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name or slug contains…" aria-label="Search communities" className="pl-9" />
        </div>
        <Select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Filter communities" className="sm:w-44">
          <option value="">All communities</option>
          <option value="ACTIVE">Active</option>
          <option value="QUARANTINED">Quarantined</option>
          <option value="BANNED">Banned</option>
        </Select>
      </div>
      {list.isPending && <Skeleton className="h-64 rounded-2xl" />}
      {list.isError && <ErrorState error={list.error} onRetry={() => list.refetch()} />}
      {list.isSuccess && items.length === 0 && <EmptyState icon={UsersRound} title="No matching communities" />}
      {items.length > 0 && <ul className="divide-y divide-border rounded-2xl border border-border">{items.map((c) => <Row key={c.id} c={c} />)}</ul>}
      <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onLoad={() => list.fetchNextPage()} />
    </div>
  );
}

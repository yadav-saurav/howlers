import { Plus, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Button, buttonClasses } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { Input, Select } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useAuth } from '@/features/auth/hooks';
import { CommunityRow } from '@/features/communities/components/CommunityRow';
import { useCommunities } from '@/features/communities/hooks';
import { useTopics } from '@/features/onboarding/hooks';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';

export default function CommunitiesPage() {
  useDocumentTitle('Communities');
  const { isAuthed } = useAuth();
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState(params.get('q') ?? '');
  const dq = useDebouncedValue(q, 300);
  const topic = params.get('topic') ?? '';
  const sort = (params.get('sort') as 'popular' | 'new') || 'popular';
  const page = Number(params.get('page') ?? 0) || 0;
  const topics = useTopics();
  const list = useCommunities({ q: dq || undefined, topic: topic || undefined, sort, page, size: 20 });

  const update = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    if (!('page' in patch)) next.delete('page');
    setParams(next, { replace: true });
  };

  useEffect(() => {
    if ((params.get('q') ?? '') !== dq) update({ q: dq || null });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dq]);

  return (
    <PageColumns wide>
      <PageHeader
        title="Communities"
        description="Find the places where people discuss what you care about."
        actions={
          isAuthed && (
            <Link to="/communities/new" className={buttonClasses('primary', 'sm')}>
              <Plus size={16} /> Create community
            </Link>
          )
        }
      />
      <div className="mb-4 grid gap-2 px-4 sm:grid-cols-[1fr_180px_140px] sm:px-0">
        <div>
          <label htmlFor="cq" className="sr-only">Search communities</label>
          <Input id="cq" type="search" placeholder="Search by name or description" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div>
          <label htmlFor="ct" className="sr-only">Topic</label>
          <Select id="ct" value={topic} onChange={(e) => update({ topic: e.target.value || null })}>
            <option value="">All topics</option>
            {topics.data?.map((t) => <option key={t.id} value={t.slug}>{t.name}</option>)}
          </Select>
        </div>
        <div>
          <label htmlFor="cs" className="sr-only">Sort</label>
          <Select id="cs" value={sort} onChange={(e) => update({ sort: e.target.value })}>
            <option value="popular">Most members</option>
            <option value="new">Newest</option>
          </Select>
        </div>
      </div>

      <div className="overflow-hidden border-y border-border bg-surface sm:rounded-lg sm:border">
        {list.isPending && (
          <div className="space-y-4 p-4">{Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-12" />)}</div>
        )}
        {list.isError && <ErrorState error={list.error} onRetry={() => list.refetch()} />}
        {list.data && list.data.items.length === 0 && (
          <EmptyState
            icon={Users}
            title={dq || topic ? 'No communities match' : 'No communities yet'}
            description={dq || topic ? 'Try a different search or topic.' : 'Start the first one.'}
            action={isAuthed && <Link to="/communities/new" className={buttonClasses('primary', 'sm')}>Create community</Link>}
          />
        )}
        {list.data && list.data.items.length > 0 && (
          <div className="divide-y divide-border">{list.data.items.map((c) => <CommunityRow key={c.id} c={c} />)}</div>
        )}
      </div>

      {list.data && list.data.totalPages > 1 && (
        <nav aria-label="Pagination" className="mt-4 flex items-center justify-between px-4 sm:px-0">
          <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => update({ page: String(page - 1) })}>
            Previous
          </Button>
          <span className="text-sm text-muted">
            Page {page + 1} of {list.data.totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= list.data.totalPages}
            onClick={() => update({ page: String(page + 1) })}
          >
            Next
          </Button>
        </nav>
      )}
    </PageColumns>
  );
}

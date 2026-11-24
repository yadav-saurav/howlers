import { Search, Users } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { Select } from '@/components/ui/Field';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { Segmented } from '@/components/ui/Tabs';
import { CommunityRow } from '@/features/communities/components/CommunityRow';
import { PostList } from '@/features/posts/components/PostList';
import type { SearchParams } from '@/features/search/api';
import { useSearch } from '@/features/search/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import type { CommunitySummary, CursorPage, PostSummary, SearchType, TimeRange, UserSummary } from '@/types/api';
import { displayName } from '@/utils/format';
import type { InfiniteData, UseInfiniteQueryResult } from '@tanstack/react-query';

function ListShell<T>({
  q,
  render,
  emptyTitle,
}: {
  q: UseInfiniteQueryResult<InfiniteData<CursorPage<T>>, Error>;
  render: (item: T) => JSX.Element;
  emptyTitle: string;
}) {
  if (q.isPending) return <div className="space-y-3 p-4">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-12" />)}</div>;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const items = q.data.pages.flatMap((p) => p.items);
  if (!items.length) return <EmptyState icon={Search} title={emptyTitle} description="Our pups sniffed everywhere. Try different words or fewer filters." mascot="sit" />;
  return (
    <>
      <div className="divide-y divide-border">{items.map(render)}</div>
      <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
    </>
  );
}

export default function SearchPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const type = (params.get('type') as SearchType) || 'posts';
  const filters: SearchParams = {
    community: params.get('community') || undefined,
    sort: (params.get('sort') as SearchParams['sort']) || 'relevance',
    t: (params.get('t') as TimeRange) || undefined,
  };
  useDocumentTitle(q ? `Search: ${q}` : 'Search');

  const posts = useSearch(q, 'posts', filters, type === 'posts');
  const communities = useSearch(q, 'communities', {}, type === 'communities');
  const users = useSearch(q, 'users', {}, type === 'users');

  const set = (k: string, v: string | null) => {
    const n = new URLSearchParams(params);
    if (v) n.set(k, v);
    else n.delete(k);
    setParams(n, { replace: true });
  };

  if (!q.trim()) {
    return (
      <PageColumns>
        <EmptyState icon={Search} title="Search Howlers" description="Use the search bar to find posts, communities and people. Prefix with # to search tags." mascot="headphones" />
      </PageColumns>
    );
  }

  return (
    <PageColumns>
      <PageHeader title={`Results for “${q}”`} description={filters.community ? `Within c/${filters.community}` : undefined} />
      <div className="mb-3 flex flex-wrap items-center gap-2 px-3 sm:px-0">
        <Segmented<SearchType>
          label="Result type"
          value={type}
          onChange={(v) => set('type', v)}
          options={[
            { value: 'posts', label: 'Posts' },
            { value: 'communities', label: 'Communities' },
            { value: 'users', label: 'People' },
          ]}
          className="flex-1"
        />
        {type === 'posts' && (
          <>
            <Select aria-label="Sort results" value={filters.sort} onChange={(e) => set('sort', e.target.value)} className="h-8 w-36 text-sm">
              <option value="relevance">Most relevant</option>
              <option value="new">Newest</option>
              <option value="top">Top</option>
            </Select>
            <Select aria-label="Time range" value={filters.t ?? ''} onChange={(e) => set('t', e.target.value || null)} className="h-8 w-32 text-sm">
              <option value="">Any time</option>
              <option value="day">Past day</option>
              <option value="week">Past week</option>
              <option value="month">Past month</option>
              <option value="year">Past year</option>
            </Select>
          </>
        )}
        {filters.community && (
          <button type="button" onClick={() => set('community', null)} className="text-xs text-muted underline hover:text-text">
            Search everywhere
          </button>
        )}
      </div>

      {type === 'posts' && <PostList query={posts as UseInfiniteQueryResult<InfiniteData<CursorPage<PostSummary>>, Error>} empty={{ icon: Search, title: 'No posts match', description: 'Our pups sniffed everywhere. Try different words or a longer time range.', mascot: 'sit' }} />}
      {type === 'communities' && (
        <div className="border-y border-border bg-surface sm:rounded-lg sm:border">
          <ListShell<CommunitySummary>
            q={communities as UseInfiniteQueryResult<InfiniteData<CursorPage<CommunitySummary>>, Error>}
            emptyTitle="No communities match"
            render={(c) => <CommunityRow key={c.id} c={c} />}
          />
        </div>
      )}
      {type === 'users' && (
        <div className="border-y border-border bg-surface sm:rounded-lg sm:border">
          <ListShell<UserSummary>
            q={users as UseInfiniteQueryResult<InfiniteData<CursorPage<UserSummary>>, Error>}
            emptyTitle="No people match"
            render={(u) => (
              <Link key={u.id} to={`/u/${u.username}`} className="flex items-center gap-3 px-4 py-3 hover:bg-surface-2/50">
                {u.avatarUrl ? <Avatar src={u.avatarUrl} name={u.username} /> : <Avatar name={u.username} />}
                <div className="min-w-0">
                  <div className="text-sm font-semibold">{displayName(u)}</div>
                  <div className="text-xs text-faint">u/{u.username}</div>
                </div>
                <Users size={15} className="ml-auto text-faint" aria-hidden="true" />
              </Link>
            )}
          />
        </div>
      )}
    </PageColumns>
  );
}

import { Compass } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { CommunityRow } from '@/features/communities/components/CommunityRow';
import { useCommunities } from '@/features/communities/hooks';
import { useTopics } from '@/features/onboarding/hooks';
import { PostList } from '@/features/posts/components/PostList';
import { useFeed } from '@/features/posts/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import { TrendingCommunitiesCard } from '@/layouts/RightRail';

function TopicsGrid() {
  const topics = useTopics();
  if (topics.isPending) {
    return (
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-16" />)}
      </div>
    );
  }
  if (topics.isError) return <ErrorState error={topics.error} onRetry={() => topics.refetch()} />;
  if (!topics.data.length) return <EmptyState icon={Compass} title="No topics yet" />;
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
      {topics.data.map((t) => (
        <Link
          key={t.id}
          to={`/communities?topic=${t.slug}`}
          className="rounded-lg border border-border bg-surface px-3.5 py-3 transition-colors hover:border-accent/50 hover:bg-surface-2/50"
        >
          <div className="text-sm font-semibold">{t.name}</div>
          {t.description && <div className="mt-0.5 line-clamp-2 text-xs text-faint">{t.description}</div>}
        </Link>
      ))}
    </div>
  );
}

function PopularCommunities() {
  const q = useCommunities({ sort: 'popular', size: 6, page: 0 });
  return (
    <Card>
      <CardHeader title="Popular communities" action={<Link to="/communities" className="text-xs text-muted hover:text-text">Directory</Link>} />
      {q.isPending && <div className="space-y-3 p-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-10" />)}</div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.items.length === 0 && <p className="px-4 py-3 text-sm text-muted">No communities yet.</p>}
      {q.data && q.data.items.length > 0 && (
        <div className="divide-y divide-border">{q.data.items.map((c) => <CommunityRow key={c.id} c={c} />)}</div>
      )}
    </Card>
  );
}

export default function ExplorePage() {
  useDocumentTitle('Explore');
  const rising = useFeed('rising', 'day');
  return (
    <PageColumns rail={<TrendingCommunitiesCard />}>
      <PageHeader title="Explore" description="Browse topics, find communities, and see what is gaining momentum." />
      <section aria-labelledby="topics-h" className="mb-8 px-4 sm:px-0">
        <h2 id="topics-h" className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted">Topics</h2>
        <TopicsGrid />
      </section>
      <section aria-labelledby="pc-h" className="mb-8 sm:px-0">
        <h2 id="pc-h" className="sr-only">Popular communities</h2>
        <PopularCommunities />
      </section>
      <section aria-labelledby="rising-h">
        <h2 id="rising-h" className="mb-3 px-4 text-xs font-semibold uppercase tracking-wider text-muted sm:px-0">Rising now</h2>
        <PostList query={rising} empty={{ title: 'Nothing rising right now', description: 'New posts gaining traction will appear here.' }} />
      </section>
    </PageColumns>
  );
}

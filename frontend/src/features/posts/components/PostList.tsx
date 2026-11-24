import type { InfiniteData, UseInfiniteQueryResult } from '@tanstack/react-query';
import { FileText, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import type { MascotPose } from '@/design-system/Mascot';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import type { CursorPage, PostSummary } from '@/types/api';
import { useUiPrefs } from '@/store/uiPrefs';
import { PostCard } from './PostCard';
import { PostRow } from './PostRow';

export function PostRowSkeleton() {
  return (
    <div className="space-y-2.5 px-4 py-4" aria-hidden="true">
      <div className="flex items-center gap-2"><Skeleton className="h-6 w-6 rounded-full" /><Skeleton className="h-3 w-40" /></div>
      <Skeleton className="h-5 w-4/5" />
      <Skeleton className="h-3 w-3/5" />
      <div className="flex gap-2"><Skeleton className="h-8 w-24 rounded-full" /><Skeleton className="h-8 w-16 rounded-full" /><Skeleton className="h-8 w-20 rounded-full" /></div>
    </div>
  );
}

interface Props {
  query: UseInfiniteQueryResult<InfiniteData<CursorPage<PostSummary>>, Error>;
  showCommunity?: boolean;
  empty: { icon?: LucideIcon; title: string; description?: string; action?: ReactNode; mascot?: MascotPose };
}

/** Renders an infinite cursor feed with loading, empty, error and load-more states. */
export function PostList({ query, showCommunity = true, empty }: Props) {
  const view = useUiPrefs((s) => s.viewMode);
  if (query.isPending) {
    return (
      <div className="divide-y divide-border">
        {Array.from({ length: 5 }).map((_, i) => (
          <PostRowSkeleton key={i} />
        ))}
      </div>
    );
  }
  if (query.isError) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const posts = query.data.pages.flatMap((p) => p.items);
  if (!posts.length) {
    return (
      <div className="rounded-2xl border border-border">
        <EmptyState icon={empty.icon ?? FileText} title={empty.title} description={empty.description} action={empty.action} mascot={empty.mascot} />
      </div>
    );
  }
  return (
    <>
      <div className="divide-y divide-border">
        {posts.map((p) => (
          <div key={p.id} className="py-1">
            {view === 'compact' ? <PostRow post={p} showCommunity={showCommunity} /> : <PostCard post={p} showCommunity={showCommunity} />}
          </div>
        ))}
      </div>
      <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onLoad={() => query.fetchNextPage()} />
      {!query.hasNextPage && posts.length > 8 && (
        <p className="py-6 text-center text-xs text-faint">You’ve reached the end.</p>
      )}
    </>
  );
}

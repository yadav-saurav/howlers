import { Headphones, Mic } from 'lucide-react';
import { Link } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import type { CommunityDetail } from '@/types/api';
import { useEpisodeList } from '../api';
import { EpisodeRow } from './EpisodeCard';

/** A community's published episodes, with a studio link for moderators. */
export function CommunityPodcasts({ c }: { c: CommunityDetail }) {
  const q = useEpisodeList({ community: c.slug, sort: 'recent' });
  const items = q.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <section className="px-2 sm:px-0">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <h2 className="mr-auto text-lg font-bold">Podcasts</h2>
        {c.viewer.canModerate && (
          <Link to={`/c/${c.slug}/podcasts/studio`} className={buttonClasses('secondary', 'sm')}>
            <Mic size={15} /> Studio
          </Link>
        )}
      </div>
      {q.isPending && <div className="space-y-2"><Skeleton className="h-24" /><Skeleton className="h-24" /></div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && items.length === 0 && (
        <EmptyState
          icon={Headphones}
          title="No episodes yet"
          description={c.viewer.canModerate ? 'Create the first episode in the studio: upload a recording and its transcript, then publish.' : 'When moderators publish episodes, they appear here.'}
          mascot="headphones"
        />
      )}
      <div className="space-y-1">{items.map((e) => <EpisodeRow key={e.id} e={e} />)}</div>
      <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
    </section>
  );
}

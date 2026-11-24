import { Headphones, History } from 'lucide-react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { useAuth } from '@/features/auth/hooks';
import { useMyCommunities } from '@/features/communities/hooks';
import { SortMenu } from '@/features/posts/components/FeedControls';
import { useEpisodeList, useListeningHistory, usePodcastHome, type ListParams } from '@/features/podcasts/api';
import { EpisodeFeature, EpisodeRow, EpisodeTile } from '@/features/podcasts/components/EpisodeCard';
import { EPISODE_TYPES } from '@/features/podcasts/helpers';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import type { EpisodeSummary, EpisodeType } from '@/types/podcasts';
import { cn } from '@/utils/format';

function Shelf({ title, items }: { title: string; items: EpisodeSummary[] }) {
  if (!items.length) return null;
  return (
    <section className="mb-8">
      <h2 className="mb-3 text-lg font-bold">{title}</h2>
      <div className="-mx-1 flex gap-4 overflow-x-auto px-1 pb-2 [scrollbar-width:thin]">
        {items.map((e) => <EpisodeTile key={e.id} e={e} />)}
      </div>
    </section>
  );
}

function Section({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="mb-8">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <h2 className="mr-auto text-lg font-bold">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function HistoryList() {
  const q = useListeningHistory(true);
  if (q.isPending) return <div className="space-y-2"><Skeleton className="h-24" /><Skeleton className="h-24" /></div>;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const items = q.data.pages.flatMap((p) => p.items);
  if (!items.length) return <EmptyState icon={History} title="Nothing played yet" description="Episodes you start listening to show up here." mascot="headphones" />;
  return (
    <>
      <div className="space-y-1">{items.map((e) => <EpisodeRow key={e.id} e={e} />)}</div>
      <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
    </>
  );
}

/** Podcast discovery: featured, continue listening, saved, recent, then a filterable catalogue. */
export default function PodcastsPage() {
  useDocumentTitle('Podcasts');
  const { isAuthed } = useAuth();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'history' && isAuthed ? 'history' : 'browse';
  const filters: ListParams = {
    type: (params.get('type') as EpisodeType) || '',
    community: params.get('community') || '',
    sort: params.get('sort') === 'popular' ? 'popular' : 'recent',
  };
  const home = usePodcastHome();
  const list = useEpisodeList(filters, tab === 'browse');
  const mine = useMyCommunities();
  const set = (k: string, v: string | null) => {
    const n = new URLSearchParams(params);
    if (v) n.set(k, v); else n.delete(k);
    setParams(n, { replace: true });
  };

  const episodes = list.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <div className="mx-auto w-full max-w-[1100px] px-3 py-5 sm:px-6">
      <div className="mb-6 flex flex-wrap items-end gap-3">
        <div className="mr-auto">
          <h1 className="flex items-center gap-2 text-2xl font-bold"><Headphones size={24} className="text-brand" /> Podcasts</h1>
          <p className="mt-1 text-sm text-muted">Long-form episodes made from community discussions, with transcripts, chapters and links to the threads they draw on.</p>
        </div>
        {isAuthed && (
          <div className="flex rounded-full bg-surface-2 p-1 text-sm font-semibold" role="tablist" aria-label="Podcast views">
            <button type="button" role="tab" aria-selected={tab === 'browse'} onClick={() => set('tab', null)} className={cn('rounded-full px-4 py-1.5', tab === 'browse' ? 'bg-surface-3' : 'text-muted')}>Browse</button>
            <button type="button" role="tab" aria-selected={tab === 'history'} onClick={() => set('tab', 'history')} className={cn('inline-flex items-center gap-1.5 rounded-full px-4 py-1.5', tab === 'history' ? 'bg-surface-3' : 'text-muted')}><History size={14} /> History</button>
          </div>
        )}
      </div>

      {tab === 'history' ? (
        <HistoryList />
      ) : (
        <>
          {home.isPending && <div className="mb-8 space-y-3"><Skeleton className="h-48 rounded-2xl" /><Skeleton className="h-56 rounded-2xl" /></div>}
          {home.isError && <div className="mb-8"><ErrorState error={home.error} onRetry={() => home.refetch()} /></div>}
          {home.data && (
            <>
              {home.data.featured.length > 0 && (
                <section className="mb-8 grid gap-3 lg:grid-cols-2">
                  {home.data.featured.slice(0, 2).map((e) => <EpisodeFeature key={e.id} e={e} />)}
                </section>
              )}
              <Shelf title="Continue listening" items={home.data.continueListening} />
              <Shelf title="Saved episodes" items={home.data.saved} />
              <Shelf title="Recently published" items={home.data.recent} />
            </>
          )}

          <Section
            title="All episodes"
            action={
              <>
                <SortMenu
                  title="Community"
                  value={filters.community || 'all'}
                  onChange={(v) => set('community', v === 'all' ? null : v)}
                  options={[{ value: 'all', label: 'All communities' }, ...(mine.data ?? []).map((c) => ({ value: c.slug, label: `c/${c.slug}` }))]}
                />
                <SortMenu
                  title="Sort by"
                  value={filters.sort ?? 'recent'}
                  onChange={(v) => set('sort', v === 'recent' ? null : v)}
                  options={[{ value: 'recent', label: 'Most recent' }, { value: 'popular', label: 'Most played' }]}
                />
              </>
            }
          >
            <div className="mb-4 flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none]" role="group" aria-label="Category">
              {[{ value: '', label: 'All' }, ...EPISODE_TYPES].map((t) => (
                <button
                  key={t.value || 'all'}
                  type="button"
                  aria-pressed={filters.type === t.value}
                  onClick={() => set('type', t.value || null)}
                  className={cn('shrink-0 rounded-full px-3 py-1.5 text-xs font-semibold', filters.type === t.value ? 'bg-text text-bg' : 'bg-surface-2 text-muted hover:text-text')}
                >
                  {t.label}
                </button>
              ))}
            </div>
            {list.isPending && <div className="space-y-2">{Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-24" />)}</div>}
            {list.isError && <ErrorState error={list.error} onRetry={() => list.refetch()} />}
            {list.data && episodes.length === 0 && (
              <EmptyState
                icon={Headphones}
                title={filters.type || filters.community ? 'No episodes match these filters' : 'No episodes published yet'}
                description="Moderators publish episodes from their community's studio. Each one is a real recording with a transcript and its sources."
                mascot="headphones"
              />
            )}
            <div className="space-y-1">{episodes.map((e) => <EpisodeRow key={e.id} e={e} />)}</div>
            <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onLoad={() => list.fetchNextPage()} />
          </Section>
        </>
      )}
    </div>
  );
}

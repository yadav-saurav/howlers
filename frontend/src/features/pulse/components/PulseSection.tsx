import { BarChart3, Plus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { RailPanel } from '@/layouts/RightRail';
import type { CommunityDetail } from '@/types/api';
import { useActivePulses, useMyActivePulses, usePulseHistory } from '../api';
import { CreatePulseDialog } from './CreatePulseDialog';
import { PulseCard } from './PulseCard';

/** Community page "Pulse" view: active pulses, then history. Mods can start new ones. */
export function PulseSection({ c }: { c: CommunityDetail }) {
  const active = useActivePulses(c.slug, c.viewer.canView);
  const history = usePulseHistory(c.slug, c.viewer.canView);
  const [creating, setCreating] = useState(false);
  const past = history.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <div className="space-y-5 px-3 sm:px-0">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-bold">Community Pulse</h2>
        {c.viewer.canModerate && <Button size="sm" onClick={() => setCreating(true)}><Plus size={15} /> New Pulse</Button>}
      </div>
      <section className="space-y-3">
        <h3 className="text-xs font-bold uppercase tracking-wider text-muted">Active</h3>
        {active.isPending && <Skeleton className="h-48 rounded-2xl" />}
        {active.isError && <ErrorState error={active.error} onRetry={() => active.refetch()} />}
        {active.data?.length === 0 && (
          <EmptyState icon={BarChart3} title="No active Pulse" description={c.viewer.canModerate ? 'Start a poll, prediction or debate for the community.' : 'Moderators run polls, predictions and debates here.'} />
        )}
        {active.data?.map((p) => <PulseCard key={p.id} p={p} />)}
      </section>
      <section className="space-y-3">
        <h3 className="text-xs font-bold uppercase tracking-wider text-muted">History</h3>
        {history.isPending && <Skeleton className="h-32 rounded-2xl" />}
        {history.isError && <ErrorState error={history.error} onRetry={() => history.refetch()} />}
        {history.data && past.length === 0 && <p className="text-sm text-muted">Closed Pulses and their results will be kept here.</p>}
        {past.map((p) => <PulseCard key={p.id} p={p} />)}
        <LoadMore hasMore={!!history.hasNextPage} loading={history.isFetchingNextPage} onLoad={() => history.fetchNextPage()} />
      </section>
      {creating && <CreatePulseDialog slug={c.slug} open={creating} onOpenChange={setCreating} />}
    </div>
  );
}

/** Right-rail panel on the community page: the soonest-closing active pulse. */
export function CommunityPulseRail({ c }: { c: CommunityDetail }) {
  const q = useActivePulses(c.slug, c.viewer.canView);
  const first = q.data?.[0];
  if (!first) return null;
  return (
    <RailPanel title="Community Pulse" action={<Link to={`/c/${c.slug}?view=pulse`} className="text-xs font-semibold text-brand hover:underline">All pulses</Link>}>
      <div className="px-3 pb-3"><PulseCard p={first} compact /></div>
    </RailPanel>
  );
}

/** Home right rail: open pulses from communities you've joined. Hidden when there are none. */
export function ActivePulsesRail() {
  const q = useMyActivePulses();
  if (!q.data?.length) return null;
  return (
    <RailPanel title="Active pulses">
      <ul className="space-y-1 px-2 pb-2">
        {q.data.map((p) => (
          <li key={p.id}>
            <Link to={`/c/${p.community.slug}?view=pulse`} className="block rounded-lg px-2 py-2 hover:bg-surface-2">
              <span className="block text-xs text-muted">c/{p.community.slug} · {p.totalVoters} votes</span>
              <span className="line-clamp-2 text-sm font-semibold leading-snug">{p.title}</span>
              {p.myOptionIds.length === 0 && <span className="mt-0.5 block text-xs font-semibold text-brand">Vote →</span>}
            </Link>
          </li>
        ))}
      </ul>
    </RailPanel>
  );
}

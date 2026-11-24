import { HandCoins, Plus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, buttonClasses } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useAuth } from '@/features/auth/hooks';
import type { CommunityDetail } from '@/types/api';
import type { PoolSummary } from '@/types/funding';
import { useCommunityFunding } from '../api';
import { ClosePoolDialog } from './ClosePoolDialog';
import { ContributeDialog } from './ContributeDialog';
import { CreatePoolDialog } from './CreatePoolDialog';
import { ModeBanner } from './Mode';
import { PolicyPanel } from './PolicyPanel';
import { PoolCard } from './PoolCard';

/** Community page "Funding" view: demo banner, pools with progress, the allocation policy, and mod/owner controls. */
export function FundingSection({ c }: { c: CommunityDetail }) {
  const q = useCommunityFunding(c.slug, c.viewer.canView);
  const { isAuthed } = useAuth();
  const [creating, setCreating] = useState(false);
  const [contributing, setContributing] = useState<PoolSummary | null>(null);
  const [closing, setClosing] = useState<PoolSummary | null>(null);

  if (q.isPending) return <div className="space-y-3 px-3 sm:px-0"><Skeleton className="h-16 rounded-2xl" /><Skeleton className="h-48 rounded-2xl" /></div>;
  if (q.isError) return <div className="px-3 sm:px-0"><ErrorState error={q.error} onRetry={() => q.refetch()} /></div>;
  const f = q.data;
  const v = f.viewer;
  const active = f.pools.filter((p) => p.status !== 'CLOSED');
  const past = f.pools.filter((p) => p.status === 'CLOSED');
  const contributeHint = !isAuthed ? 'Sign in and join to contribute.' : !c.viewer.isMember ? 'Join the community to contribute.' : null;

  return (
    <div className="space-y-5 px-3 sm:px-0">
      <ModeBanner status={f.status} />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-lg font-bold">Moderator funding</h2>
          <p className="text-xs text-muted">Members chip in for the people who keep c/{c.slug} running. Every allocation is public.</p>
        </div>
        <div className="flex gap-2">
          {isAuthed && <Link to="/funding" className={buttonClasses('ghost', 'sm')}>My contributions</Link>}
          {v.canCreatePool && <Button size="sm" onClick={() => setCreating(true)}><Plus size={15} aria-hidden="true" /> New pool</Button>}
        </div>
      </div>

      <section className="space-y-3" aria-labelledby="active-pools">
        <h3 id="active-pools" className="text-xs font-bold uppercase tracking-wider text-muted">Current pools</h3>
        {active.length === 0 && (
          <EmptyState
            icon={HandCoins}
            title="No open pool"
            description={v.canCreatePool ? 'Start a pool with a purpose, a goal and a period.' : 'Moderators can open a pool for hosting, tools or their time.'}
          />
        )}
        {active.map((p) => (
          <PoolCard
            key={p.id}
            pool={p}
            onContribute={v.canContribute ? () => setContributing(p) : undefined}
            extra={
              <>
                {v.canClose && <Button size="sm" variant="ghost" onClick={() => setClosing(p)}>{p.status === 'OPEN' ? 'Close now…' : 'Allocate…'}</Button>}
                {!v.canContribute && p.status === 'OPEN' && contributeHint && <span className="self-center text-xs text-muted">{contributeHint}</span>}
              </>
            }
          />
        ))}
      </section>

      <PolicyPanel slug={c.slug} policy={f.policy} canEdit={v.canSetPolicy} />

      {past.length > 0 && (
        <section className="space-y-3" aria-labelledby="past-pools">
          <h3 id="past-pools" className="text-xs font-bold uppercase tracking-wider text-muted">Past periods</h3>
          {past.map((p) => <PoolCard key={p.id} pool={p} />)}
        </section>
      )}

      {creating && <CreatePoolDialog slug={c.slug} open={creating} onOpenChange={setCreating} />}
      {contributing && <ContributeDialog pool={contributing} open onOpenChange={(o) => !o && setContributing(null)} />}
      {closing && (
        <ClosePoolDialog pool={closing} moderators={c.moderators.map((m) => m.user)} open onOpenChange={(o) => !o && setClosing(null)} />
      )}
    </div>
  );
}

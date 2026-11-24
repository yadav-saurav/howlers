import { HandCoins } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useMyFunding } from '@/features/funding/api';
import { ContributeDialog } from '@/features/funding/components/ContributeDialog';
import { ModeBanner } from '@/features/funding/components/Mode';
import { MyContributions } from '@/features/funding/components/MyContributions';
import { PoolCard } from '@/features/funding/components/PoolCard';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns } from '@/layouts/PageColumns';
import type { PoolSummary } from '@/types/funding';

/** Moderator funding hub: my contributions (cancel monthly ones) and open pools in communities I've joined. */
export default function FundingPage() {
  useDocumentTitle('Moderator funding');
  const q = useMyFunding();
  const [contributing, setContributing] = useState<PoolSummary | null>(null);

  return (
    <PageColumns>
      <div className="space-y-5 px-3 pt-2 sm:px-0">
        <header>
          <h1 className="text-2xl font-bold">Moderator funding</h1>
          <p className="text-sm text-muted">Support the moderators of communities you belong to. Every pool publishes who received what, and why.</p>
          <Link to="/funding/payouts" className="mt-1 inline-block text-sm font-semibold text-brand hover:underline">
            Moderating? See your payouts and payout account
          </Link>
        </header>
        <ModeBanner status={q.data?.status} />
        {q.isPending && <><Skeleton className="h-40 rounded-2xl" /><Skeleton className="h-24 rounded-2xl" /></>}
        {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
        {q.data && (
          <>
            <section aria-labelledby="open-pools" className="space-y-3">
              <h2 id="open-pools" className="text-xs font-bold uppercase tracking-wider text-muted">Open pools in your communities</h2>
              {q.data.openPools.length === 0 ? (
                <EmptyState
                  icon={HandCoins}
                  title="No open pools"
                  description="When moderators of a community you’ve joined open a pool, it shows up here."
                  action={<Link to="/communities" className={buttonClasses('secondary', 'sm')}>Browse communities</Link>}
                />
              ) : (
                q.data.openPools.map((p) => (
                  <PoolCard key={p.id} pool={p} showCommunity onContribute={q.data.status.enabled ? () => setContributing(p) : undefined} />
                ))
              )}
            </section>
            <section aria-labelledby="my-contributions" className="space-y-3">
              <h2 id="my-contributions" className="text-xs font-bold uppercase tracking-wider text-muted">My contributions</h2>
              <MyContributions contributions={q.data.contributions} subscriptions={q.data.subscriptions} />
            </section>
          </>
        )}
      </div>
      {contributing && <ContributeDialog pool={contributing} open onOpenChange={(o) => !o && setContributing(null)} />}
    </PageColumns>
  );
}

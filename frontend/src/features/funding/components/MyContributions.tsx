import { HandCoins, Repeat } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Display';
import { Dialog } from '@/components/ui/Dialog';
import { EmptyState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { Contribution, FundingSubscription } from '@/types/funding';
import { formatDate } from '@/utils/format';
import { fundingApi, useFundingMutation } from '../api';
import { formatMoney } from '../money';
import { MoneyAmount } from './Mode';

const SUB_STATUS: Record<FundingSubscription['status'], string> = { ACTIVE: 'Active', CANCELLED: 'Cancelled', ENDED: 'Ended' };

function endedWhy(s: FundingSubscription): string | null {
  if (s.status === 'CANCELLED') return s.cancelledBy === 'PROVIDER' ? 'Cancelled by the payment provider' : 'Cancelled by you';
  if (s.status === 'ENDED') return 'Ended with the pool’s period';
  return null;
}

/** Monthly contributions (with cancel) and the history of every demo charge. */
export function MyContributions({ contributions, subscriptions }: { contributions: Contribution[]; subscriptions: FundingSubscription[] }) {
  const [cancelling, setCancelling] = useState<FundingSubscription | null>(null);
  const cancel = useFundingMutation((id: number) => fundingApi.cancel(id));

  if (contributions.length === 0 && subscriptions.length === 0) {
    return <EmptyState icon={HandCoins} title="No contributions yet" description="Contributions you make to a community’s moderator pool show up here." />;
  }
  return (
    <div className="space-y-5">
      {subscriptions.length > 0 && (
        <section aria-labelledby="subs-h">
          <h3 id="subs-h" className="mb-2 text-xs font-bold uppercase tracking-wider text-muted">Monthly</h3>
          <ul className="space-y-2">
            {subscriptions.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-surface px-3 py-2.5">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-1.5 text-sm">
                    <Repeat size={14} className="text-muted" aria-hidden="true" />
                    <MoneyAmount m={s.amount} /> <span className="text-muted">/ month to</span>
                    <Link to={`/funding/pools/${s.pool.id}`} className="font-semibold hover:underline">{s.pool.title}</Link>
                    <span className="text-muted">in c/{s.pool.community.slug}</span>
                  </div>
                  <div className="mt-0.5 text-xs text-muted">
                    {s.cyclesCharged} {s.cyclesCharged === 1 ? 'month' : 'months'} recorded
                    {s.status === 'ACTIVE' && s.billedBy === 'howlers' && s.nextChargeAt && <> · next demo charge {formatDate(s.nextChargeAt)}</>}
                    {s.status === 'ACTIVE' && s.billedBy === 'provider' && <> · billed monthly by Stripe</>}
                    {endedWhy(s) && <> · {endedWhy(s)}</>}
                    {s.status === 'ACTIVE' && s.paymentFailedAt && (
                      <span className="block text-warning">The last renewal payment failed ({formatDate(s.paymentFailedAt)}); Stripe will retry it.</span>
                    )}
                  </div>
                </div>
                {s.status === 'ACTIVE' ? (
                  <Button size="sm" variant="secondary" onClick={() => setCancelling(s)}>Cancel monthly</Button>
                ) : (
                  <Badge>{SUB_STATUS[s.status]}</Badge>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
      {contributions.length > 0 && (
        <section aria-labelledby="hist-h">
          <h3 id="hist-h" className="mb-2 text-xs font-bold uppercase tracking-wider text-muted">History</h3>
          <ul className="divide-y divide-border rounded-xl border border-border bg-surface">
            {contributions.map((c) => (
              <li key={c.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
                <span className="min-w-0">
                  <Link to={`/funding/pools/${c.pool.id}`} className="font-semibold hover:underline">{c.pool.title}</Link>
                  <span className="text-muted"> · c/{c.pool.community.slug} · {formatDate(c.createdAt)}</span>
                  <span className="block text-xs text-faint">
                    {c.kind === 'RECURRING' ? `Monthly, month ${c.cycle}` : 'One time'} · {c.showName ? 'Named in the report' : 'Anonymous'}
                    {c.refunded && c.refunded.amountMinor > 0 && <> · refunded {formatMoney(c.refunded)}</>}
                  </span>
                </span>
                <MoneyAmount m={c.amount} />
              </li>
            ))}
          </ul>
        </section>
      )}
      {cancelling && (
        <Dialog
          open
          onOpenChange={(o) => !o && setCancelling(null)}
          size="sm"
          title="Cancel monthly contribution?"
          description={cancelling.billedBy === 'provider'
            ? `Stripe won't charge the monthly ${formatMoney(cancelling.amount)} again. Months already paid stay in the pool and its ledger.`
            : `No further demo charges of ${formatMoney(cancelling.amount)} will be recorded. Months already recorded stay in the pool and its ledger.`}
          footer={
            <>
              <Button variant="ghost" onClick={() => setCancelling(null)}>Keep it</Button>
              <Button
                variant="danger"
                loading={cancel.isPending}
                onClick={() =>
                  cancel.mutate(cancelling.id, {
                    onSuccess: () => { toast.success('Monthly contribution cancelled'); setCancelling(null); },
                    onError: (e) => toast.error(errorMessage(e)),
                  })
                }
              >
                Cancel monthly
              </Button>
            </>
          }
        >
          <p className="text-sm text-muted">
            {cancelling.amount.mode === 'live'
              ? 'The subscription is cancelled at Stripe right away.'
              : cancelling.amount.mode === 'test'
                ? 'This is a Stripe test-mode subscription: no real money was ever charged.'
                : 'This is a demo contribution: no real money was ever charged.'}
          </p>
        </Dialog>
      )}
    </div>
  );
}

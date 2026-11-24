import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/Display';
import type { Payout } from '@/types/payouts';
import { formatDate } from '@/utils/format';
import { payoutTone } from '../payouts';
import { MoneyAmount } from './Mode';

/** The moderator's share of each closed pool and whether it was paid out or is held. */
export function PayoutList({ payouts }: { payouts: Payout[] }) {
  if (payouts.length === 0) {
    return <p className="text-sm text-muted">No allocations yet. When a pool you moderate closes, your share appears here.</p>;
  }
  return (
    <ul className="divide-y divide-border rounded-2xl border border-border">
      {payouts.map((p) => (
        <li key={p.id} className="flex flex-wrap items-start justify-between gap-2 px-4 py-3">
          <div className="min-w-0">
            <Link to={`/funding/pools/${p.pool.id}`} className="font-semibold hover:underline">{p.pool.title}</Link>
            <div className="text-xs text-muted">
              c/{p.pool.community?.slug} · {p.paidAt ? `paid ${formatDate(p.paidAt)}` : `allocated ${formatDate(p.createdAt)}`}
            </div>
            {p.reason && p.status !== 'PAID' && <p className="mt-1 text-xs text-muted">{p.reason}</p>}
          </div>
          <div className="flex flex-col items-end gap-1">
            <MoneyAmount m={p.amount} className="justify-end" />
            <Badge tone={payoutTone(p.status)}>{p.label}</Badge>
            {p.reversed.amountMinor > 0 && <span className="text-xs text-muted">Reversed: <MoneyAmount m={p.reversed} /></span>}
          </div>
        </li>
      ))}
    </ul>
  );
}

import { ClipboardCheck } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Badge, Skeleton } from '@/components/ui/Display';
import { Textarea } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { errorMessage } from '@/services/apiError';
import type { ReviewFlag } from '@/types/payouts';
import { formatDate } from '@/utils/format';
import { REVIEW_KIND_LABELS } from '../payouts';
import { payoutsApi, useAdminPayoutMutation, useReviewFlags } from '../payoutsApi';
import { MoneyAmount } from './Mode';

function ReviewCard({ f }: { f: ReviewFlag }) {
  const [note, setNote] = useState('');
  const resolve = useAdminPayoutMutation((n: string) => payoutsApi.resolve(f.id, n));
  return (
    <li className="space-y-2 rounded-2xl border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={f.kind === 'TRANSFER_FAILING' ? 'danger' : 'warning'}>{REVIEW_KIND_LABELS[f.kind] ?? f.kind}</Badge>
          <Link to={`/funding/pools/${f.pool.id}`} className="text-sm font-semibold hover:underline">{f.pool.title}</Link>
          {f.moderator && <span className="text-xs text-muted">u/{f.moderator.username}</span>}
        </div>
        <MoneyAmount m={f.amount} />
      </div>
      <p className="text-sm">{f.detail}</p>
      <p className="text-xs text-faint">Raised {formatDate(f.createdAt)}{f.payoutId != null && ` · payout #${f.payoutId}`}</p>
      {f.resolvedAt ? (
        <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm">
          <span className="font-semibold">Resolved</span> {formatDate(f.resolvedAt)}{f.resolvedBy && ` by u/${f.resolvedBy.username}`}: {f.resolutionNote}
        </p>
      ) : (
        <form className="space-y-2" onSubmit={(e) => { e.preventDefault(); resolve.mutate(note.trim()); }}>
          <label className="block text-xs font-medium text-muted" htmlFor={`review-${f.id}`}>What was done (recorded in the audit log)</label>
          <Textarea id={`review-${f.id}`} rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. Reversed tr_… in the Stripe dashboard, or absorbed by the platform." />
          {resolve.isError && <p role="alert" className="text-sm text-danger">{errorMessage(resolve.error)}</p>}
          <Button size="sm" type="submit" loading={resolve.isPending} disabled={note.trim().length < 5}>Mark as handled</Button>
        </form>
      )}
    </li>
  );
}

/**
 * Payouts that need a person: refunds after a payout (never clawed back automatically), reversed transfers, and transfers
 * that keep failing.
 */
export function PayoutReviews() {
  const [open, setOpen] = useState(true);
  const q = useReviewFlags(open);
  return (
    <section aria-labelledby="reviews-h" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="reviews-h" className="text-sm font-semibold">Payout reviews</h2>
        <div className="flex gap-1" role="group" aria-label="Which reviews">
          {[true, false].map((v) => (
            <button key={String(v)} type="button" aria-pressed={open === v} onClick={() => setOpen(v)}
              className={open === v ? 'rounded-full bg-surface-3 px-3 py-1 text-xs font-semibold' : 'rounded-full px-3 py-1 text-xs text-muted hover:bg-surface-2'}>
              {v ? 'Open' : 'All'}
            </button>
          ))}
        </div>
      </div>
      {q.isPending && <Skeleton className="h-24 rounded-2xl" />}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.length === 0 && (
        <EmptyState icon={ClipboardCheck} title="Nothing to review" description="Refunds after a payout, reversed transfers and transfers that keep failing show up here." />
      )}
      {q.data && q.data.length > 0 && <ul className="space-y-2">{q.data.map((f) => <ReviewCard key={f.id} f={f} />)}</ul>}
    </section>
  );
}

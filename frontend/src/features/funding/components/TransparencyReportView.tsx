import { AlertTriangle, CheckCircle2 } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { TransparencyReport } from '@/types/funding';
import { formatDate, pluralize } from '@/utils/format';
import { Badge } from '@/components/ui/Display';
import { POLICY_LABELS } from '../money';
import { payoutTone } from '../payouts';
import { MoneyAmount } from './Mode';

function Tile({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rounded-xl bg-surface-2/60 px-4 py-3">
      <div className="text-lg">{children}</div>
      <div className="text-xs text-muted">{label}</div>
    </div>
  );
}

/** Totals, contributors, allocations and the full ledger for one pool and period. Everything is public to viewers. */
export function TransparencyReportView({ r }: { r: TransparencyReport }) {
  const rec = r.reconciliation;
  const pay = r.payouts?.applicable ? r.payouts : null;
  const testMode = r.pool.mode !== 'live';
  return (
    <div className="space-y-5">
      <section aria-label="Totals" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Tile label="Raised"><MoneyAmount m={r.raised} /></Tile>
        <Tile label={pay ? 'Allocated' : 'Allocated, not paid out'}><MoneyAmount m={r.allocated} /></Tile>
        <Tile label="Held in pool"><MoneyAmount m={rec.poolBalance} /></Tile>
        <Tile label={`${pluralize(r.contributionCount, 'contribution')} · ${pluralize(r.contributorCount, 'contributor')}`}>
          <span className="font-semibold tabular-nums">{r.contributionCount} · {r.contributorCount}</span>
        </Tile>
      </section>

      <section
        aria-label="Reconciliation"
        className={rec.reconciles
          ? 'flex items-start gap-2 rounded-xl border border-accent/30 bg-accent/10 px-3 py-2 text-sm'
          : 'flex items-start gap-2 rounded-xl border border-danger/40 bg-danger/10 px-3 py-2 text-sm'}
      >
        {rec.reconciles ? <CheckCircle2 size={17} className="mt-0.5 shrink-0 text-accent" aria-hidden="true" /> : <AlertTriangle size={17} className="mt-0.5 shrink-0 text-danger" aria-hidden="true" />}
        <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
          <strong>{rec.reconciles ? 'Ledger reconciles.' : 'Ledger does not reconcile.'}</strong>
          <span className="text-muted">Contributions</span> <MoneyAmount m={rec.contributionsTotal} />
          <span className="text-muted">= ledger in</span> <MoneyAmount m={rec.ledgerIn} />
          <span className="text-muted">; allocations</span> <MoneyAmount m={rec.allocationsTotal} />
          {rec.refundsTotal && rec.refundsTotal.amountMinor > 0 && (
            <><span className="text-muted">+ refunds</span> <MoneyAmount m={rec.refundsTotal} /></>
          )}
          <span className="text-muted">= ledger out</span> <MoneyAmount m={rec.ledgerOut} />
          {rec.shortfall && rec.shortfall.amountMinor > 0 && (
            <span className="basis-full text-xs text-muted">
              {/* A refund after the close reverses money that was already allocated as an obligation. */}
              Refunded after the pool closed: <MoneyAmount m={rec.shortfall} />. Those allocations are no longer covered.
            </span>
          )}
        </div>
      </section>

      <section aria-labelledby="contributors-h">
        <h3 id="contributors-h" className="mb-1 text-xs font-bold uppercase tracking-wider text-muted">Contributors</h3>
        <p className="text-sm">
          {r.contributorCount === 0
            ? 'No contributions yet.'
            : <>{r.anonymousContributors} anonymous{r.namedContributors.length > 0 && <>, plus {r.namedContributors.map((u, i) => (
              <span key={u.id}>{i > 0 && ', '}<Link to={`/u/${u.username}`} className="font-semibold hover:underline">u/{u.username}</Link></span>
            ))} (chose to be named)</>}.</>}
        </p>
        <p className="mt-0.5 text-xs text-faint">Contributors are anonymous unless they opt in. Amounts are never shown next to a name.</p>
      </section>

      <section aria-labelledby="alloc-h">
        <h3 id="alloc-h" className="mb-1 text-xs font-bold uppercase tracking-wider text-muted">Allocations</h3>
        {pay ? (
          <div className="mb-2 space-y-1 text-xs text-faint">
            <p>
              Each share is sent to the moderator’s connected Stripe account{testMode ? ' in Stripe test mode (no real money)' : ''}. A moderator
              without one has their share held until they connect it.
            </p>
            <p className={pay.reconciles ? 'flex flex-wrap items-center gap-1 text-muted' : 'flex flex-wrap items-center gap-1 text-danger'}>
              {pay.reconciles ? <CheckCircle2 size={13} className="text-accent" aria-hidden="true" /> : <AlertTriangle size={13} aria-hidden="true" />}
              <span>{pay.reconciles ? 'Payouts reconcile:' : 'Payouts do not reconcile:'} allocated</span> <MoneyAmount m={pay.allocated} />
              <span>= {testMode ? 'paid out (test mode)' : 'paid out'}</span> <MoneyAmount m={pay.transferred} />
              <span>+ held</span> <MoneyAmount m={pay.held} />
            </p>
          </div>
        ) : (
          <p className="mb-2 text-xs text-faint">Allocated, not paid out: each row is a recorded obligation to a moderator. No money is sent to moderators by this site.</p>
        )}
        {r.allocations.length === 0 ? (
          <p className="text-sm text-muted">
            {r.pool.status === 'OPEN' ? 'Allocated when the period ends on ' + formatDate(r.pool.periodEnd) + '.' : r.pool.status === 'AWAITING_ALLOCATION' ? 'The period has ended; waiting for the owner’s manual allocation.' : 'Nothing was allocated.'}
          </p>
        ) : (
          <ScrollRegion label="Allocations table">
            <table className="w-full min-w-[420px] text-sm">
              <caption className="sr-only">Allocations per moderator</caption>
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="py-1 font-medium">Moderator</th>
                  <th className="py-1 font-medium">Policy</th>
                  <th className="py-1 font-medium" title="Reports resolved / appeals handled / score">Activity</th>
                  <th className="py-1 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody>
                {r.allocations.map((a, i) => (
                  <tr key={a.moderator?.id ?? i} className="border-t border-border align-top">
                    <td className="py-2">{a.moderator ? <Link to={`/u/${a.moderator.username}`} className="font-semibold hover:underline">u/{a.moderator.username}</Link> : 'Deleted account'}</td>
                    <td className="py-2 text-muted">{POLICY_LABELS[a.policy]}</td>
                    <td className="py-2 tabular-nums text-muted">
                      {a.activityScore != null ? `${a.reportsResolved ?? 0} reports · ${a.appealsHandled ?? 0} appeals · ${a.activityScore} pts` : '–'}
                    </td>
                    <td className="py-2 text-right">
                      <MoneyAmount m={a.amount} className="justify-end" />
                      {a.payout && <div className="mt-1"><Badge tone={payoutTone(a.payout.status)}>{a.payout.label}</Badge></div>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        {r.closedNote && <p className="mt-2 rounded-lg bg-surface-2 px-3 py-2 text-sm"><span className="font-semibold">Note: </span>{r.closedNote}</p>}
      </section>

      <section aria-labelledby="ledger-h">
        <h3 id="ledger-h" className="mb-1 text-xs font-bold uppercase tracking-wider text-muted">Ledger (append-only)</h3>
        <p className="mb-2 text-xs text-faint">Each row moves an amount from one account to another: contributions into the pool, allocations out to moderators, refunds back to contributors. Rows are never edited or deleted; a refund is a new row.</p>
        {r.ledger.length === 0 ? (
          <p className="text-sm text-muted">No entries yet.</p>
        ) : (
          <ScrollRegion label="Ledger table">
            <table className="w-full min-w-[520px] text-sm">
              <caption className="sr-only">Ledger entries</caption>
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="py-1 font-medium">#</th>
                  <th className="py-1 font-medium">Date</th>
                  <th className="py-1 font-medium">From</th>
                  <th className="py-1 font-medium">To</th>
                  <th className="py-1 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody>
                {r.ledger.map((l) => (
                  <tr key={l.id} className="border-t border-border">
                    <td className="py-1.5 tabular-nums text-faint">{l.id}</td>
                    <td className="py-1.5 whitespace-nowrap text-muted">{formatDate(l.createdAt)}</td>
                    <td className="py-1.5">{l.entryType === 'REFUND' ? <>{l.debitLabel} <span className="text-xs text-muted">(refund)</span></> : l.debitLabel}</td>
                    <td className="py-1.5">
                      {l.creditLabel}
                      {l.entryType === 'PAYOUT' && <span className="text-xs text-muted"> (payout)</span>}
                      {l.entryType === 'PAYOUT_REVERSAL' && <span className="text-xs text-muted"> (reversed transfer)</span>}
                    </td>
                    <td className="py-1.5 text-right"><MoneyAmount m={l.amount} className="justify-end" /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        {r.ledgerTruncated && <p className="mt-1 text-xs text-muted">Showing the first 1,000 entries.</p>}
      </section>
    </div>
  );
}

/**
 * A horizontally scrollable table wrapper that keyboard users can focus and scroll (axe `scrollable-region-focusable`).
 * A focusable named region is the documented pattern for this, so the non-interactive tabindex rule is waived here.
 */
function ScrollRegion({ label, children }: { label: string; children: ReactNode }) {
  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
    <div className="overflow-x-auto rounded focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand" tabIndex={0} role="region" aria-label={label}>
      {children}
    </div>
  );
}

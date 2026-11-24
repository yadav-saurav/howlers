import { CalendarClock, FileText, HandCoins, Lock, UsersRound } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Button, buttonClasses } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Display';
import type { PoolSummary } from '@/types/funding';
import { formatDate, pluralize, timeUntil } from '@/utils/format';
import { POLICY_LABELS, STATUS_LABELS, contributeSuffix, progressPercent } from '../money';
import { MoneyAmount } from './Mode';

/** A pool with its progress towards the goal. Actions are passed in, so the card never shows a button that can't work. */
export function PoolCard({
  pool,
  showCommunity = false,
  onContribute,
  extra,
  reportLink = true,
}: {
  pool: PoolSummary;
  showCommunity?: boolean;
  reportLink?: boolean;
  onContribute?: () => void;
  extra?: ReactNode;
}) {
  const pct = progressPercent(pool.raised.amountMinor, pool.goal.amountMinor);
  const open = pool.status === 'OPEN';
  return (
    <article className="rounded-2xl border border-border bg-surface p-4" aria-labelledby={`pool-${pool.id}-title`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          {showCommunity && (
            <Link to={`/c/${pool.community.slug}?view=funding`} className="text-xs font-semibold text-muted hover:underline">
              c/{pool.community.slug}
            </Link>
          )}
          <h3 id={`pool-${pool.id}-title`} className="text-[17px] font-semibold leading-snug">{pool.title}</h3>
        </div>
        <Badge tone={open ? 'accent' : pool.status === 'CLOSED' ? 'neutral' : 'warning'}>
          {pool.status === 'CLOSED' && <Lock size={11} aria-hidden="true" />}
          {STATUS_LABELS[pool.status]}
        </Badge>
      </div>
      <p className="mt-1 whitespace-pre-line text-sm text-muted">{pool.purpose}</p>

      <div className="mt-4">
        <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 text-sm">
          <span className="inline-flex flex-wrap items-center gap-1.5">
            <MoneyAmount m={pool.raised} /> <span className="text-muted">raised of</span> <MoneyAmount m={pool.goal} />
          </span>
          <span className="tabular-nums text-muted">{pct}%</span>
        </div>
        <div
          role="progressbar"
          aria-label={`${pool.title}: ${pct}% of goal`}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={pct}
          className="mt-2 h-2 overflow-hidden rounded-full bg-surface-2"
        >
          <div className="h-full rounded-full bg-accent transition-[width] motion-reduce:transition-none" style={{ width: `${pct}%` }} />
        </div>
      </div>

      <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
        <li className="inline-flex items-center gap-1"><UsersRound size={13} aria-hidden="true" /> {pool.contributorCount} {pluralize(pool.contributorCount, 'contributor')}</li>
        <li className="inline-flex items-center gap-1">
          <CalendarClock size={13} aria-hidden="true" />
          {formatDate(pool.periodStart)} – {formatDate(pool.periodEnd)}
          {open && <span>({timeUntil(pool.periodEnd)} left)</span>}
        </li>
        {pool.closedPolicy && <li>Allocated by {POLICY_LABELS[pool.closedPolicy].toLowerCase()}</li>}
      </ul>

      <div className="mt-4 flex flex-wrap gap-2">
        {open && onContribute && (
          <Button size="sm" onClick={onContribute}>
            <HandCoins size={15} aria-hidden="true" /> Contribute{contributeSuffix(pool.mode ?? 'demo')}
          </Button>
        )}
        {reportLink && (
          <Link to={`/funding/pools/${pool.id}`} className={buttonClasses('secondary', 'sm')}>
            <FileText size={15} aria-hidden="true" /> Transparency report
          </Link>
        )}
        {extra}
      </div>
    </article>
  );
}

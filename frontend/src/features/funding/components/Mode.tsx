import { CreditCard, FlaskConical, PowerOff } from 'lucide-react';
import type { FundingMode, FundingStatus, Money } from '@/types/funding';
import { cn } from '@/utils/format';
import { formatMoney, labelFor, shortModeLabel } from '../money';

/**
 * The badge next to every non-live amount. Its text comes from the server's label for that row's mode
 * ("DEMO, no real money" or "TEST MODE: no real money"); `full` spells it out, otherwise "Demo" / "Test".
 */
export function ModeBadge({ mode, label, full = false, className }: { mode: FundingMode; label?: string | null; full?: boolean; className?: string }) {
  const text = label ?? labelFor(mode);
  const short = shortModeLabel(mode);
  if (!text || !short) return null;
  return (
    <span
      title={text}
      className={cn(
        'inline-flex h-5 shrink-0 items-center rounded-sm border border-warning/40 bg-warning/15 px-1.5 text-[10px] font-bold uppercase leading-none tracking-wide text-warning',
        className,
      )}
    >
      {full ? text : short}
      {!full && <span className="sr-only">, no real money</span>}
    </span>
  );
}

/** An amount with its mode label. Use this for every amount on every funding screen. */
export function MoneyAmount({ m, full = false, className }: { m: Money; full?: boolean; className?: string }) {
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1.5', className)}>
      <span className="font-semibold tabular-nums">{formatMoney(m)}</span>
      {m.demo && <ModeBadge mode={m.mode ?? 'demo'} label={m.label} full={full} />}
    </span>
  );
}

/** Page-level banner: impossible to miss, shown above every funding view. Says what the active provider does with money. */
export function ModeBanner({ status }: { status: FundingStatus | undefined }) {
  if (status && !status.enabled) {
    return (
      <div role="note" className="flex items-start gap-3 rounded-2xl border border-border bg-surface-2/60 px-4 py-3 text-sm">
        <PowerOff size={18} className="mt-0.5 shrink-0 text-muted" aria-hidden="true" />
        <div>
          <p className="font-semibold">Moderator funding is turned off on this server</p>
          <p className="text-muted">{status.notice ?? 'Existing reports stay readable; nothing can be contributed.'}</p>
        </div>
      </div>
    );
  }
  const mode: FundingMode = status?.mode ?? 'demo';
  const label = status?.label ?? labelFor(mode);
  const payouts = status?.payouts ?? 'Allocated, not paid out: allocations are recorded obligations and no money is sent to moderators.';
  if (mode === 'live') {
    return (
      <div role="note" className="flex items-start gap-3 rounded-2xl border border-border bg-surface-2/60 px-4 py-3 text-sm">
        <CreditCard size={18} className="mt-0.5 shrink-0 text-muted" aria-hidden="true" />
        <div>
          <p className="font-semibold">Payments through Stripe</p>
          <p className="text-text">{status?.notice}</p>
          <p className="mt-1 text-muted">{payouts}</p>
        </div>
      </div>
    );
  }
  return (
    <div role="note" aria-label={label ?? undefined} className="flex items-start gap-3 rounded-2xl border-2 border-warning/50 bg-warning/10 px-4 py-3 text-sm">
      <FlaskConical size={18} className="mt-0.5 shrink-0 text-warning" aria-hidden="true" />
      <div>
        <p className="font-bold uppercase tracking-wide text-warning">{label}</p>
        <p className="text-text">
          {status?.notice ??
            'Demo mode: contributions are simulated, always succeed, and no real money moves. Nothing is charged and nobody is paid.'}
        </p>
        <p className="mt-1 text-muted">{payouts}</p>
      </div>
    </div>
  );
}

import { useQueryClient } from '@tanstack/react-query';
import { BadgeCheck, CircleDashed, ExternalLink, RefreshCw, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Display';
import { errorMessage } from '@/services/apiError';
import type { MyPayouts } from '@/types/payouts';
import { timeAgo } from '@/utils/format';
import { connectStatusInfo, isStripeOnboardingUrl } from '../payouts';
import { payoutKeys, payoutsApi } from '../payoutsApi';

/**
 * The moderator's Stripe Express account: status, what Stripe still needs, and the button to start or resume Stripe's
 * hosted onboarding. Howlers never sees bank or identity details; Stripe collects them on its own page.
 */
export function ConnectAccountCard({ data }: { data: MyPayouts }) {
  const qc = useQueryClient();
  const [busy, setBusy] = useState<'start' | 'check' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const a = data.account;
  const info = connectStatusInfo(a.status);
  const Icon = a.status === 'ENABLED' ? BadgeCheck : a.status === 'REQUIREMENTS_DUE' ? TriangleAlert : CircleDashed;

  const start = async () => {
    setBusy('start');
    setError(null);
    try {
      const link = await payoutsApi.onboarding();
      if (!isStripeOnboardingUrl(link.url)) throw new Error('The server returned an unexpected onboarding address.');
      window.location.assign(link.url);
    } catch (e) {
      setError(errorMessage(e));
      setBusy(null);
    }
  };

  const check = async () => {
    setBusy('check');
    setError(null);
    try {
      qc.setQueryData(payoutKeys.mine, await payoutsApi.refresh());
      void qc.invalidateQueries({ queryKey: ['funding'] });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  };

  if (!data.connectEnabled) {
    return (
      <section aria-labelledby="connect-h" className="rounded-2xl border border-border bg-surface-2/40 px-4 py-3 text-sm">
        <h2 id="connect-h" className="font-semibold">Payout account</h2>
        <p className="mt-1 text-muted">{data.notice}</p>
      </section>
    );
  }

  return (
    <section aria-labelledby="connect-h" className="space-y-3 rounded-2xl border border-border px-4 py-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <Icon size={20} className={a.status === 'ENABLED' ? 'mt-0.5 text-accent' : a.status === 'REQUIREMENTS_DUE' ? 'mt-0.5 text-warning' : 'mt-0.5 text-muted'} aria-hidden="true" />
          <div>
            <h2 id="connect-h" className="font-semibold">{info.title}</h2>
            <p className="text-sm text-muted">{a.message}</p>
            <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-xs text-faint">
              {a.accountHint && <Badge>{a.accountHint}</Badge>}
              {data.mode === 'test' && <Badge tone="warning">Stripe test mode</Badge>}
              {a.syncedAt && <span>Checked {timeAgo(a.syncedAt)}</span>}
              {a.lastPayoutStatus && <span>Last bank payout: {a.lastPayoutStatus}</span>}
            </div>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {info.action && data.eligible && (
            <Button size="sm" onClick={start} loading={busy === 'start'} disabled={busy !== null}>
              <ExternalLink size={14} aria-hidden="true" /> {info.action}
            </Button>
          )}
          {a.status !== 'NOT_STARTED' && (
            <Button size="sm" variant="secondary" onClick={check} loading={busy === 'check'} disabled={busy !== null}>
              <RefreshCw size={14} aria-hidden="true" /> Check status
            </Button>
          )}
        </div>
      </div>
      {!data.eligible && a.status === 'NOT_STARTED' && (
        <p className="text-sm text-muted">Payout accounts are for moderators. Once you moderate a community, you can connect one here.</p>
      )}
      <p className="text-xs text-faint">
        Onboarding happens on Stripe’s own page. Howlers stores only the account id and its status, never your bank or identity details.
      </p>
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}
    </section>
  );
}

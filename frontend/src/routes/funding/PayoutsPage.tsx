import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Info } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Skeleton } from '@/components/ui/Display';
import { ErrorState } from '@/components/ui/States';
import { ConnectAccountCard } from '@/features/funding/components/ConnectAccountCard';
import { MoneyAmount } from '@/features/funding/components/Mode';
import { PayoutList } from '@/features/funding/components/PayoutList';
import { parseConnectReturn } from '@/features/funding/payouts';
import { payoutKeys, payoutsApi, useMyPayouts } from '@/features/funding/payoutsApi';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns } from '@/layouts/PageColumns';
import { errorMessage } from '@/services/apiError';

/**
 * The moderator's payouts: their Stripe Connect account (onboarding status) and their share of each closed pool, paid
 * out or held. Stripe sends the browser back here with ?connect=return (re-read the account) or ?connect=refresh (the
 * single-use link expired; start again).
 */
export default function PayoutsPage() {
  useDocumentTitle('Moderator payouts');
  const q = useMyPayouts();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const ret = parseConnectReturn(params);
  const [notice, setNotice] = useState<string | null>(null);
  const handled = useRef(false);

  useEffect(() => {
    if (!ret || handled.current) return;
    handled.current = true;
    setParams({}, { replace: true });
    if (ret === 'refresh') {
      setNotice('That onboarding link expired or was already used. Press “Resume onboarding” to get a new one.');
      return;
    }
    setNotice('Checking your account with Stripe…');
    payoutsApi.refresh()
      .then((d) => {
        qc.setQueryData(payoutKeys.mine, d);
        void qc.invalidateQueries({ queryKey: ['funding'] });
        setNotice(d.account.status === 'ENABLED' ? 'Your payout account is ready. Held shares are being sent.' : 'Back from Stripe. ' + d.account.message);
      })
      .catch((e) => setNotice(errorMessage(e)));
  }, [ret, setParams, qc]);

  return (
    <PageColumns>
      <div className="space-y-5 px-3 pt-2 sm:px-0">
        <header>
          <Link to="/funding" className="mb-1 inline-flex items-center gap-1 text-xs text-muted hover:underline"><ArrowLeft size={12} aria-hidden="true" /> Moderator funding</Link>
          <h1 className="text-2xl font-bold">Moderator payouts</h1>
          <p className="text-sm text-muted">Your share of each closed pool, sent to your Stripe account once it’s connected.</p>
        </header>
        {notice && (
          <div role="status" className="flex items-start gap-2 rounded-xl border border-info/30 bg-info/10 px-3 py-2 text-sm">
            <Info size={16} className="mt-0.5 shrink-0 text-info" aria-hidden="true" /> {notice}
          </div>
        )}
        {q.isPending && <><Skeleton className="h-32 rounded-2xl" /><Skeleton className="h-24 rounded-2xl" /></>}
        {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
        {q.data && (
          <>
            {q.data.connectEnabled && q.data.mode === 'test' && (
              <p className="rounded-xl border-2 border-warning/50 bg-warning/10 px-3 py-2 text-sm font-semibold text-warning">{q.data.notice}</p>
            )}
            <ConnectAccountCard data={q.data} />
            {(q.data.held.some((m) => m.amountMinor > 0) || q.data.paid.some((m) => m.amountMinor > 0)) && (
              <section aria-label="Totals" className="grid grid-cols-2 gap-2">
                <div className="rounded-xl bg-surface-2/60 px-4 py-3">
                  <div className="flex flex-col gap-1">{q.data.paid.map((m) => <MoneyAmount key={`${m.currency}-${m.mode}`} m={m} />)}</div>
                  <div className="text-xs text-muted">{q.data.mode === 'live' ? 'Paid out' : 'Paid out (test mode)'}</div>
                </div>
                <div className="rounded-xl bg-surface-2/60 px-4 py-3">
                  <div className="flex flex-col gap-1">{q.data.held.map((m) => <MoneyAmount key={`${m.currency}-${m.mode}`} m={m} />)}</div>
                  <div className="text-xs text-muted">Held</div>
                </div>
              </section>
            )}
            <section aria-labelledby="payouts-h" className="space-y-2">
              <h2 id="payouts-h" className="text-xs font-bold uppercase tracking-wider text-muted">Your allocations</h2>
              <PayoutList payouts={q.data.payouts} />
            </section>
          </>
        )}
      </div>
    </PageColumns>
  );
}

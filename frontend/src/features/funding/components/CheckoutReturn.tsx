import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, Clock, X, XCircle } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { errorMessage } from '@/services/apiError';
import type { CheckoutStatus } from '@/types/funding';

import { fundingApi, fundingKeys } from '../api';
import { CONFIRM_POLL_MS, formatMoney, isFinalCheckoutState, parseCheckoutReturn } from '../money';

type View =
  | { phase: 'checking' }
  | { phase: 'done'; status: CheckoutStatus }
  | { phase: 'waiting'; status: CheckoutStatus | null }
  | { phase: 'error'; message: string };

/**
 * Shown on the pool page after Stripe Checkout sends the contributor back. Success: polls the confirm endpoint (the
 * server asks Stripe itself) until the contribution is confirmed, then refreshes the pool. Cancelled: expires the
 * session so it can't be paid later, and says nothing was charged.
 */
export function CheckoutReturn() {
  const [params, setParams] = useSearchParams();
  const ret = parseCheckoutReturn(params);
  const qc = useQueryClient();
  const [view, setView] = useState<View>({ phase: 'checking' });
  const key = ret ? (ret.kind === 'success' ? ret.sessionId : `cancel-${ret.pendingId}`) : null;
  const started = useRef<string | null>(null);

  useEffect(() => {
    if (!ret || !key || started.current === key) return;
    started.current = key;
    let stop = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const finish = (s: CheckoutStatus) => {
      setView({ phase: 'done', status: s });
      void qc.invalidateQueries({ queryKey: fundingKeys.all });
    };
    if (ret.kind === 'cancelled') {
      fundingApi.cancelCheckout(ret.pendingId).then(finish, (e) => setView({ phase: 'error', message: errorMessage(e) }));
      return;
    }
    const poll = (attempt: number) => {
      fundingApi.confirmCheckout(ret.sessionId).then(
        (s) => {
          if (stop) return;
          if (isFinalCheckoutState(s.status)) return finish(s);
          if (attempt >= CONFIRM_POLL_MS.length) return setView({ phase: 'waiting', status: s });
          setView({ phase: 'waiting', status: s });
          timer = setTimeout(() => poll(attempt + 1), CONFIRM_POLL_MS[attempt]);
        },
        (e) => !stop && setView({ phase: 'error', message: errorMessage(e) }),
      );
    };
    poll(0);
    return () => {
      stop = true;
      if (timer) clearTimeout(timer);
      started.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  if (!ret) return null;
  const dismiss = () => {
    const next = new URLSearchParams(params);
    ['checkout', 'session_id', 'pending'].forEach((k) => next.delete(k));
    setParams(next, { replace: true });
  };

  let tone = 'border-border bg-surface-2/60';
  let icon = <Clock size={18} className="mt-0.5 shrink-0 text-muted" aria-hidden="true" />;
  let title: string;
  let body: string;
  if (view.phase === 'checking') {
    title = ret.kind === 'success' ? 'Confirming your payment with Stripe…' : 'Cancelling the checkout…';
    body = 'This takes a moment.';
  } else if (view.phase === 'error') {
    tone = 'border-danger/40 bg-danger/10';
    icon = <XCircle size={18} className="mt-0.5 shrink-0 text-danger" aria-hidden="true" />;
    title = 'Couldn’t check this payment';
    body = `${view.message} If you paid, it’s recorded as soon as Stripe reports it.`;
  } else if (view.phase === 'waiting') {
    title = 'Payment pending';
    body = 'Stripe hasn’t confirmed this payment yet. It doesn’t count until it’s confirmed; this page will show it once Stripe reports it.';
  } else {
    const s = view.status;
    const amount = `${formatMoney(s.amount)}${s.kind === 'RECURRING' ? ' a month' : ''}`;
    if (s.status === 'CONFIRMED') {
      tone = 'border-accent/30 bg-accent/10';
      icon = <CheckCircle2 size={18} className="mt-0.5 shrink-0 text-accent" aria-hidden="true" />;
      title = 'Thank you, your contribution is confirmed';
      body = `${amount} to “${s.pool.title}”.${s.amount.label ? ` ${s.amount.label}.` : ''}`;
    } else {
      tone = 'border-border bg-surface-2/60';
      icon = <XCircle size={18} className="mt-0.5 shrink-0 text-muted" aria-hidden="true" />;
      title = s.status === 'CANCELLED' ? 'Checkout cancelled' : s.status === 'EXPIRED' ? 'Checkout expired' : 'Payment not recorded';
      body = s.reason ?? 'Nothing was charged.';
    }
  }

  return (
    <div role="status" aria-live="polite" className={`flex items-start gap-3 rounded-2xl border px-4 py-3 text-sm ${tone}`}>
      {icon}
      <div className="min-w-0 flex-1">
        <p className="font-semibold">{title}</p>
        <p className="text-muted">{body}</p>
      </div>
      {view.phase !== 'checking' && (
        <Button size="sm" variant="ghost" onClick={dismiss} aria-label="Dismiss">
          <X size={15} aria-hidden="true" />
        </Button>
      )}
    </div>
  );
}

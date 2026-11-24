import { CheckCircle2, CircleSlash, Copy, Play } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Badge, Skeleton } from '@/components/ui/Display';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { timeAgo } from '@/utils/format';
import { payoutsApi, useAdminPayoutMutation, useStripeStatus } from '../payoutsApi';

function Flag({ ok, label, hint }: { ok: boolean; label: string; hint?: string }) {
  return (
    <li className="flex items-start gap-2 text-sm">
      {ok ? <CheckCircle2 size={16} className="mt-0.5 shrink-0 text-accent" aria-hidden="true" /> : <CircleSlash size={16} className="mt-0.5 shrink-0 text-muted" aria-hidden="true" />}
      <span>
        <span className="font-medium">{label}</span>
        <span className="sr-only">{ok ? ': yes' : ': no'}</span>
        {hint && <span className="block text-xs text-muted">{hint}</span>}
      </span>
    </li>
  );
}

function EventList({ title, events }: { title: string; events: string[] }) {
  return (
    <div>
      <div className="mb-1 text-xs font-semibold text-muted">{title}</div>
      <ul className="flex flex-wrap gap-1">
        {events.map((e) => <li key={e}><code className="rounded bg-surface-2 px-1.5 py-0.5 text-xs">{e}</code></li>)}
      </ul>
    </div>
  );
}

/**
 * Admin "Stripe status": mode, which secrets are set (never their values), the last webhook received, Connect on or off,
 * payout counts, and exactly what to enter in the Stripe dashboard.
 */
export function StripeStatusPanel() {
  const q = useStripeStatus();
  const run = useAdminPayoutMutation(() => payoutsApi.runPayouts());
  const [copied, setCopied] = useState(false);
  if (q.isPending) return <Skeleton className="h-64 rounded-2xl" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const s = q.data;
  const isStripe = s.provider === 'stripe';

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(s.webhookUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <section aria-labelledby="stripe-h" className="space-y-4 rounded-2xl border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="stripe-h" className="text-sm font-semibold">Stripe status</h2>
        <div className="flex flex-wrap gap-1.5">
          <Badge>{s.fundingEnabled ? `Provider: ${s.provider}` : 'Funding off'}</Badge>
          {s.mode && <Badge tone={s.mode === 'live' ? 'danger' : 'warning'}>{s.mode === 'live' ? 'LIVE: real money' : s.mode === 'test' ? 'Test mode' : 'Demo'}</Badge>}
          <Badge tone={s.connectEnabled ? 'accent' : 'neutral'}>{s.connectEnabled ? 'Connect payouts on' : 'Connect payouts off'}</Badge>
        </div>
      </div>

      <ul className="grid gap-2 sm:grid-cols-2">
        <Flag ok={s.secretKeySet} label="Secret key set" hint={s.secretKeySet ? undefined : 'STRIPE_SECRET_KEY (server-side only)'} />
        <Flag ok={s.webhookSecretSet} label="Webhook secret set" hint={s.webhookSecretSet ? undefined : 'STRIPE_WEBHOOK_SECRET: without it, Stripe webhooks are refused'} />
        <Flag ok={s.connectWebhookSecretSet} label="Connected-accounts webhook secret set"
          hint="Optional: STRIPE_CONNECT_WEBHOOK_SECRET, only if the dashboard has a separate connected-accounts endpoint" />
        <Flag ok={s.connectEnabled} label="Stripe Connect payouts enabled" hint={s.connectNote ?? (s.connectCountry ? `New accounts are created in ${s.connectCountry}` : undefined)} />
        <Flag ok={!!s.lastWebhook} label={s.lastWebhook ? `Last webhook ${timeAgo(s.lastWebhook.receivedAt)}` : 'No webhook received yet'}
          hint={s.lastWebhook ? `${s.lastWebhook.type}: ${s.lastWebhook.outcome || 'no outcome'} · ${s.webhooksLast24h} in the last 24 h` : undefined} />
      </ul>

      {isStripe && (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
          {[
            ['Connected accounts', `${s.accountsEnabled} / ${s.connectedAccounts} enabled`],
            ['Payouts paid', String(s.payoutsPaid)],
            ['Payouts held', String(s.payoutsHeld)],
            ['Failed, retrying', String(s.payoutsFailed)],
            ['Open reviews', String(s.openReviews)],
          ].map(([label, value]) => (
            <div key={label} className="rounded-xl bg-surface-2/60 px-3 py-2">
              <div className="font-semibold tabular-nums">{value}</div>
              <div className="text-xs text-muted">{label}</div>
            </div>
          ))}
        </div>
      )}

      <div className="space-y-2 rounded-xl bg-surface-2/40 p-3">
        <div className="text-xs font-semibold text-muted">Webhook endpoint for the Stripe dashboard</div>
        <div className="flex flex-wrap items-center gap-2">
          <code className="break-all rounded bg-surface-2 px-2 py-1 text-xs">{s.webhookUrl}</code>
          <Button size="sm" variant="ghost" onClick={copy}><Copy size={14} aria-hidden="true" /> {copied ? 'Copied' : 'Copy'}</Button>
        </div>
        <p className="text-xs text-faint">If the API is served from a different host than the site, use that host. Step-by-step setup: docs/DEPLOYMENT.md, “Stripe webhooks and Connect”.</p>
        <EventList title="Events from your account" events={s.platformEvents} />
        <EventList title="Events from connected accounts" events={s.connectEvents} />
      </div>

      {s.connectEnabled && (
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" variant="secondary" loading={run.isPending} onClick={() => run.mutate(undefined, {
            onSuccess: (r) => toast.success(`Payout run: ${r.paid} paid, ${r.stillHeld} still held, ${r.failed} failed`),
            onError: (e) => toast.error(errorMessage(e)),
          })}>
            <Play size={14} aria-hidden="true" /> Run payouts now
          </Button>
          <span className="text-xs text-muted">The job also runs every minute.</span>
        </div>
      )}
    </section>
  );
}

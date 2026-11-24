import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Field, Input } from '@/components/ui/Field';
import { Switch } from '@/components/ui/Switch';
import { Segmented } from '@/components/ui/Tabs';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { PoolSummary } from '@/types/funding';
import { cn } from '@/utils/format';
import { fundingApi, useFundingMutation } from '../api';
import { contributeSuffix, contributionBounds, formatMinor, labelFor, parseToMinor, presets } from '../money';
import { ModeBadge } from './Mode';

type Kind = 'once' | 'monthly';

/**
 * Contribute to a pool, once or monthly. Demo: recorded at once, nothing is charged. Stripe: the browser goes to
 * Stripe's hosted Checkout (card details never touch this site) and comes back to the pool page, which confirms it.
 */
export function ContributeDialog({ pool, open, onOpenChange }: { pool: PoolSummary; open: boolean; onOpenChange: (o: boolean) => void }) {
  const currency = pool.goal.currency;
  const [kind, setKind] = useState<Kind>('once');
  const [text, setText] = useState('');
  const [preset, setPreset] = useState<number | null>(presets(currency)[1] ?? null);
  const [showName, setShowName] = useState(false);
  const { min, max } = contributionBounds(currency);
  const amount = preset ?? parseToMinor(text, currency);
  const error =
    preset == null && text.trim() !== ''
      ? amount == null
        ? 'Enter an amount like 12 or 12.50'
        : amount < min || amount > max
          ? `Between ${formatMinor(min, currency)} and ${formatMinor(max, currency)}`
          : undefined
      : undefined;
  const valid = amount != null && amount >= min && amount <= max;

  const mode = pool.mode ?? 'demo';
  const label = pool.goal.label ?? labelFor(mode);
  const [redirecting, setRedirecting] = useState(false);
  const contribute = useFundingMutation(() =>
    fundingApi.contribute(pool.id, { amountMinor: amount ?? 0, recurring: kind === 'monthly', showName }),
  );

  const submit = () =>
    contribute.mutate(undefined, {
      onSuccess: (result) => {
        if (result.checkout) {
          // Hosted checkout: nothing is recorded until Stripe confirms the payment.
          setRedirecting(true);
          window.location.assign(result.checkout.url);
          return;
        }
        toast.success(kind === 'monthly' ? 'Monthly demo contribution set up. No real money moved.' : 'Demo contribution recorded. No real money moved.');
        onOpenChange(false);
        setText('');
      },
      onError: (e) => toast.error(errorMessage(e)),
    });

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={`Contribute to “${pool.title}”`}
      description={label ? `c/${pool.community.slug} · ${label}` : `c/${pool.community.slug}`}
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button disabled={!valid || redirecting} loading={contribute.isPending || redirecting} onClick={submit}>
            {redirecting
              ? 'Opening Stripe Checkout…'
              : valid
                ? `${kind === 'monthly' ? 'Start monthly' : 'Contribute'} ${formatMinor(amount ?? 0, currency)}${contributeSuffix(mode)}`
                : `Contribute${contributeSuffix(mode)}`}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {mode === 'demo' && (
          <p className="flex flex-wrap items-center gap-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-sm">
            <ModeBadge mode="demo" label={label} full />
            <span>You won’t be charged. This simulates a contribution so the pool, allocations and ledger can be tried out.</span>
          </p>
        )}
        {mode === 'test' && (
          <p className="flex flex-wrap items-center gap-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-sm">
            <ModeBadge mode="test" label={label} full />
            <span>
              You’ll continue on Stripe’s test checkout. Use a Stripe test card (for example 4242 4242 4242 4242); real cards are declined and
              no real money moves.
            </span>
          </p>
        )}
        {mode === 'live' && (
          <p className="rounded-lg border border-border bg-surface-2/60 px-3 py-2 text-sm">
            You’ll continue on Stripe’s secure checkout; this site never sees your card details. The contribution counts once Stripe confirms it.
          </p>
        )}
        <Segmented<Kind>
          label="Contribution frequency"
          value={kind}
          onChange={setKind}
          options={[
            { value: 'once', label: 'One time' },
            { value: 'monthly', label: 'Monthly' },
          ]}
        />
        <div>
          <div className="mb-1.5 text-sm font-medium">Amount</div>
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Preset amounts">
            {presets(currency).map((p) => (
              <button
                key={p}
                type="button"
                role="radio"
                aria-checked={preset === p}
                onClick={() => { setPreset(p); setText(''); }}
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-semibold',
                  preset === p ? 'bg-brand text-brand-fg' : 'bg-surface-2 text-muted hover:text-text',
                )}
              >
                {formatMinor(p, currency)}
              </button>
            ))}
          </div>
        </div>
        <Field label={`Other amount (${currency})`} error={error} hint={`Between ${formatMinor(min, currency)} and ${formatMinor(max, currency)}.`} optional>
          {(a) => (
            <Input
              {...a}
              inputMode="decimal"
              autoComplete="off"
              placeholder={currency === 'JPY' ? '1500' : '12.50'}
              value={text}
              onChange={(e) => { setText(e.target.value); setPreset(null); }}
            />
          )}
        </Field>
        <Switch
          label="Show my name in the transparency report"
          description="Off by default: you’re counted as an anonymous contributor and your amount is never shown next to your name."
          checked={showName}
          onCheckedChange={setShowName}
        />
        {kind === 'monthly' && (
          <p className="text-xs text-muted">
            {mode === 'demo' ? 'The first month is recorded now' : 'The first month is charged by Stripe now'}, then once a month until you cancel (from Moderator funding → My contributions) or the pool’s period ends on{' '}
            {new Date(pool.periodEnd).toLocaleDateString()}.
          </p>
        )}
      </div>
    </Dialog>
  );
}

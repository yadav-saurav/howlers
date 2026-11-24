import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Field, Input, Textarea } from '@/components/ui/Field';
import { Spinner } from '@/components/ui/Spinner';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { UserSummary } from '@/types/api';
import type { PoolSummary } from '@/types/funding';
import { fundingApi, useAllocationPreview, useFundingMutation } from '../api';
import { POLICY_LABELS, formatMinor, parseToMinor, sumMinor } from '../money';
import { MoneyAmount } from './Mode';

/**
 * Owner closes a pool now. For EQUAL and ACTIVITY it shows exactly what the server would allocate (the preview endpoint
 * runs the same code). For MANUAL the owner types each moderator's share and a public note; the sum must match.
 */
export function ClosePoolDialog({ pool, moderators, open, onOpenChange }: {
  pool: PoolSummary;
  moderators: UserSummary[];
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const preview = useAllocationPreview(pool.id, open);
  const currency = pool.goal.currency;
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [note, setNote] = useState('');
  const manual = preview.data?.policy === 'MANUAL';
  const total = preview.data?.total.amountMinor ?? 0;
  const parsed = moderators.map((m) => ({ username: m.username, minor: parseToMinor(amounts[m.username] ?? '0', currency) }));
  const anyInvalid = parsed.some((p) => p.minor == null);
  const allocated = sumMinor(parsed.map((p) => p.minor ?? 0));
  const remaining = total - allocated;
  const manualValid = !anyInvalid && remaining === 0 && note.trim().length >= 10;

  const close = useFundingMutation(() =>
    fundingApi.close(
      pool.id,
      manual ? { note: note.trim(), allocations: parsed.filter((p) => (p.minor ?? 0) > 0).map((p) => ({ username: p.username, amountMinor: p.minor ?? 0 })) } : undefined,
    ),
  );

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="lg"
      title={`Close “${pool.title}”`}
      description="Closing ends contributions and monthly charges, and records the allocations permanently. This can’t be undone."
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button
            variant="danger"
            disabled={!preview.data || (manual && !manualValid)}
            loading={close.isPending}
            onClick={() =>
              close.mutate(undefined, {
                onSuccess: () => { toast.success('Pool closed and allocations recorded'); onOpenChange(false); },
                onError: (e) => toast.error(errorMessage(e)),
              })
            }
          >
            Close and allocate
          </Button>
        </>
      }
    >
      {preview.isPending && <div className="flex justify-center py-8"><Spinner /></div>}
      {preview.isError && <ErrorState error={preview.error} onRetry={() => preview.refetch()} />}
      {preview.data && (
        <div className="space-y-3">
          <p className="flex flex-wrap items-center gap-1.5 text-sm">
            Policy: <strong>{POLICY_LABELS[preview.data.policy]}</strong> · total <MoneyAmount m={preview.data.total} />
          </p>
          {preview.data.note && <p className="rounded-lg bg-surface-2 px-3 py-2 text-xs text-muted">{preview.data.note}</p>}
          {!manual ? (
            <table className="w-full text-sm">
              <caption className="sr-only">Planned allocations</caption>
              <thead className="text-left text-xs text-muted">
                <tr><th className="py-1 font-medium">Moderator</th><th className="py-1 font-medium">Score</th><th className="py-1 text-right font-medium">Amount</th></tr>
              </thead>
              <tbody>
                {preview.data.allocations.map((a, i) => (
                  <tr key={a.moderator?.id ?? i} className="border-t border-border">
                    <td className="py-1.5">{a.moderator ? `u/${a.moderator.username}` : 'Deleted account'}</td>
                    <td className="py-1.5 tabular-nums text-muted">{a.activityScore ?? '–'}</td>
                    <td className="py-1.5 text-right"><MoneyAmount m={a.amount} className="justify-end" /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <div className="space-y-2">
              {moderators.map((m) => (
                <Field key={m.id} label={`u/${m.username}`} error={parsed.find((p) => p.username === m.username)?.minor == null ? 'Enter an amount like 12.50' : undefined}>
                  {(a) => (
                    <Input {...a} inputMode="decimal" placeholder="0" value={amounts[m.username] ?? ''}
                      onChange={(e) => setAmounts({ ...amounts, [m.username]: e.target.value })} className="max-w-[180px]" />
                  )}
                </Field>
              ))}
              <p className={remaining === 0 ? 'text-sm text-accent' : 'text-sm text-warning'} aria-live="polite">
                {remaining === 0 ? 'Allocations add up to the total.' : remaining > 0
                  ? `${formatMinor(remaining, currency)} left to allocate (demo).`
                  : `${formatMinor(-remaining, currency)} over the total (demo).`}
              </p>
              <Field label="Public note" hint="Required. Explain the split; it’s shown in the transparency report (at least 10 characters).">
                {(a) => <Textarea {...a} value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} rows={3} />}
              </Field>
            </div>
          )}
        </div>
      )}
    </Dialog>
  );
}

import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Field, Input, Select, Textarea } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { fundingApi, useFundingMutation, useFundingStatus } from '../api';
import { DEMO_LABEL, EXPONENTS, formatMinor, parseToMinor } from '../money';

const PERIODS = [
  { days: 30, label: '30 days' },
  { days: 90, label: '3 months' },
  { days: 180, label: '6 months' },
  { days: 365, label: '1 year' },
];

/** Moderators start a pool: what it's for, a goal and a period. The server re-checks every rule. */
export function CreatePoolDialog({ slug, open, onOpenChange }: { slug: string; open: boolean; onOpenChange: (o: boolean) => void }) {
  const status = useFundingStatus();
  const modeLabel = status.data ? status.data.label : DEMO_LABEL;
  const [title, setTitle] = useState('');
  const [purpose, setPurpose] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [goal, setGoal] = useState('');
  const [days, setDays] = useState(90);
  const goalMinor = parseToMinor(goal, currency);
  const goalError = goal.trim() && (goalMinor == null || goalMinor <= 0) ? 'Enter a goal like 500 or 500.00' : undefined;
  const valid = title.trim().length > 0 && purpose.trim().length >= 10 && goalMinor != null && goalMinor > 0;

  const create = useFundingMutation(() =>
    fundingApi.createPool(slug, {
      title: title.trim(),
      purpose: purpose.trim(),
      currency,
      goalMinor: goalMinor ?? 0,
      periodEnd: new Date(Date.now() + days * 86_400_000).toISOString(),
    }),
  );

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title="New funding pool"
      description={`Members contribute toward a goal; when the period ends the pool is allocated by the community's policy.${modeLabel ? ` ${modeLabel}.` : ''}`}
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button
            disabled={!valid}
            loading={create.isPending}
            onClick={() =>
              create.mutate(undefined, {
                onSuccess: () => {
                  toast.success('Pool created');
                  setTitle('');
                  setPurpose('');
                  setGoal('');
                  onOpenChange(false);
                },
                onError: (e) => toast.error(errorMessage(e)),
              })
            }
          >
            Create pool
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Title">{(a) => <Input {...a} value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} />}</Field>
        <Field label="Purpose" hint="What the money is for. Shown to everyone on the pool and its report (10–2000 characters).">
          {(a) => <Textarea {...a} value={purpose} onChange={(e) => setPurpose(e.target.value)} maxLength={2000} rows={3} />}
        </Field>
        <div className="grid gap-3 sm:grid-cols-[120px_1fr]">
          <Field label="Currency">
            {(a) => (
              <Select {...a} value={currency} onChange={(e) => setCurrency(e.target.value)}>
                {Object.keys(EXPONENTS).map((c) => <option key={c} value={c}>{c}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Goal" error={goalError} hint={goalMinor ? `${formatMinor(goalMinor, currency)} (demo)` : undefined}>
            {(a) => <Input {...a} inputMode="decimal" value={goal} onChange={(e) => setGoal(e.target.value)} placeholder="500" />}
          </Field>
        </div>
        <Field label="Period" hint="Starts now. The pool closes and is allocated automatically when it ends.">
          {(a) => (
            <Select {...a} value={days} onChange={(e) => setDays(Number(e.target.value))}>
              {PERIODS.map((p) => <option key={p.days} value={p.days}>{p.label}</option>)}
            </Select>
          )}
        </Field>
      </div>
    </Dialog>
  );
}

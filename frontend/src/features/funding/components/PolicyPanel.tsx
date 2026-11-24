import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Field, Input } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { AllocationPolicy, FundingPolicy } from '@/types/funding';
import { cn, formatDate } from '@/utils/format';
import { fundingApi, useFundingMutation } from '../api';
import { POLICY_HINTS, POLICY_LABELS } from '../money';

const POLICIES: AllocationPolicy[] = ['EQUAL', 'ACTIVITY', 'MANUAL'];

/** The community's allocation policy. Everyone can read it; only the owner can change it. */
export function PolicyPanel({ slug, policy, canEdit }: { slug: string; policy: FundingPolicy; canEdit: boolean }) {
  const [choice, setChoice] = useState<AllocationPolicy>(policy.policy);
  const [cap, setCap] = useState(String(policy.activityCap));
  useEffect(() => {
    setChoice(policy.policy);
    setCap(String(policy.activityCap));
  }, [policy.policy, policy.activityCap]);
  const capNum = Number(cap);
  const capValid = Number.isInteger(capNum) && capNum >= 1 && capNum <= 1000;
  const dirty = choice !== policy.policy || (capValid && capNum !== policy.activityCap);
  const save = useFundingMutation(() => fundingApi.setPolicy(slug, { policy: choice, activityCap: capNum }));

  return (
    <section className="rounded-2xl border border-border bg-surface p-4" aria-labelledby="funding-policy">
      <h3 id="funding-policy" className="font-semibold">Allocation policy</h3>
      <p className="mt-0.5 text-xs text-muted">
        Applied when a pool’s period ends. Recorded with every allocation in the transparency report.
        {policy.updatedBy && policy.updatedAt && <> Last changed by u/{policy.updatedBy.username} on {formatDate(policy.updatedAt)}.</>}
      </p>
      {canEdit ? (
        <div className="mt-3 space-y-2" role="radiogroup" aria-label="Allocation policy">
          {POLICIES.map((p) => (
            <button
              key={p}
              type="button"
              role="radio"
              aria-checked={choice === p}
              onClick={() => setChoice(p)}
              className={cn(
                'block w-full rounded-xl border px-3 py-2 text-left',
                choice === p ? 'border-brand bg-brand/10' : 'border-border hover:bg-surface-2',
              )}
            >
              <span className="block text-sm font-semibold">{POLICY_LABELS[p]}</span>
              <span className="block text-xs text-muted">{POLICY_HINTS[p]}</span>
            </button>
          ))}
          {choice === 'ACTIVITY' && (
            <Field label="Cap per component (points)" error={capValid ? undefined : 'A whole number from 1 to 1000'} hint="Stops sheer volume from dominating: reports and appeals each count up to this many points.">
              {(a) => <Input {...a} inputMode="numeric" value={cap} onChange={(e) => setCap(e.target.value)} className="max-w-[120px]" />}
            </Field>
          )}
          <Button
            size="sm"
            disabled={!dirty || !capValid}
            loading={save.isPending}
            onClick={() => save.mutate(undefined, { onSuccess: () => toast.success('Policy saved'), onError: (e) => toast.error(errorMessage(e)) })}
          >
            Save policy
          </Button>
        </div>
      ) : (
        <div className="mt-3">
          <p className="text-sm font-semibold">{POLICY_LABELS[policy.policy]}{policy.policy === 'ACTIVITY' && ` (cap ${policy.activityCap})`}</p>
          <p className="text-xs text-muted">{POLICY_HINTS[policy.policy]}</p>
        </div>
      )}
    </section>
  );
}

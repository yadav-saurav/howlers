import { zodResolver } from '@hookform/resolvers/zod';
import { Check, X } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Field, FormAlert, Input } from '@/components/ui/Field';
import { Spinner } from '@/components/ui/Spinner';
import type { Me } from '@/types/api';
import { applyServerErrors } from '@/utils/forms';
import { registerSchema, type RegisterValues } from '@/utils/validation';
import { useRegister, useUsernameAvailability, type Availability } from '../hooks';

function AvailabilityHint({ state, reason }: { state: Availability; reason: string | null }) {
  if (state === 'checking') return <span className="inline-flex items-center gap-1.5"><Spinner size={12} /> Checking availability…</span>;
  if (state === 'available') return <span className="inline-flex items-center gap-1 text-accent"><Check size={13} /> Available</span>;
  if (state === 'taken') return <span className="inline-flex items-center gap-1 text-danger"><X size={13} /> {reason ?? 'Already taken'}</span>;
  if (state === 'error') return <span>Couldn’t check right now — we’ll verify when you submit.</span>;
  return <span>3–21 characters: letters, numbers, underscores. Permanent.</span>;
}

export function RegisterForm({ onSuccess }: { onSuccess: (me: Me) => void }) {
  const reg = useRegister();
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, setError, watch, formState } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    mode: 'onBlur',
  });
  const avail = useUsernameAvailability(watch('username') ?? '');

  return (
    <form
      noValidate
      className="space-y-4"
      onSubmit={handleSubmit((v) => {
        setFormError(null);
        if (avail.state === 'taken') {
          setError('username', { type: 'server', message: avail.reason ?? 'That username is taken' });
          return;
        }
        reg.mutate(
          { username: v.username, email: v.email, password: v.password, displayName: v.displayName || undefined },
          {
            onSuccess: (r) => onSuccess(r.user),
            onError: (e) => setFormError(applyServerErrors(e, setError, ['username', 'email', 'password', 'displayName'])),
          },
        );
      })}
    >
      <FormAlert message={formError} />
      <Field label="Username" error={formState.errors.username?.message} hint={<AvailabilityHint {...avail} />}>
        {(a) => <Input {...a} autoComplete="username" {...register('username')} />}
      </Field>
      <Field label="Email" error={formState.errors.email?.message} hint="Used for verification and password resets. Never shown publicly.">
        {(a) => <Input {...a} type="email" autoComplete="email" {...register('email')} />}
      </Field>
      <Field label="Password" error={formState.errors.password?.message} hint="At least 8 characters with a letter and a number.">
        {(a) => <Input {...a} type="password" autoComplete="new-password" {...register('password')} />}
      </Field>
      <Field label="Display name" optional error={formState.errors.displayName?.message}>
        {(a) => <Input {...a} autoComplete="nickname" {...register('displayName')} />}
      </Field>
      <Button type="submit" className="w-full" loading={reg.isPending}>Create account</Button>
      <p className="text-center text-xs text-faint">
        By joining you agree to the <Link to="/terms" className="underline">Terms</Link> and <Link to="/privacy" className="underline">Privacy policy</Link>.
      </p>
    </form>
  );
}

import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Field, FormAlert, Input } from '@/components/ui/Field';
import { accountSecurityApi } from '@/features/accountSecurity/api';
import { useAcceptSession } from '@/features/accountSecurity/hooks';
import { classifyCode, isMfaChallenge, normaliseCode } from '@/features/accountSecurity/logic';
import { ApiError, errorMessage } from '@/services/apiError';
import type { MfaChallenge } from '@/types/accountSecurity';
import type { Me } from '@/types/api';
import { applyServerErrors } from '@/utils/forms';
import { loginSchema, type LoginValues } from '@/utils/validation';
import { useLogin } from '../hooks';

export function LoginForm({ onSuccess }: { onSuccess: (me: Me) => void }) {
  const login = useLogin();
  const [formError, setFormError] = useState<string | null>(null);
  const [challenge, setChallenge] = useState<MfaChallenge | null>(null);
  const { register, handleSubmit, setError, formState } = useForm<LoginValues>({ resolver: zodResolver(loginSchema) });
  if (challenge) {
    return (
      <SecondStepForm
        challenge={challenge}
        onSuccess={onSuccess}
        onRestart={(message) => {
          setChallenge(null);
          setFormError(message);
        }}
      />
    );
  }
  return (
    <form
      noValidate
      className="space-y-4"
      onSubmit={handleSubmit((v) => {
        setFormError(null);
        login.mutate(v, {
          onSuccess: (r) => (isMfaChallenge(r) ? setChallenge(r) : onSuccess(r.user)),
          onError: (e) => setFormError(applyServerErrors(e, setError, ['login', 'password'])),
        });
      })}
    >
      <FormAlert message={formError} />
      <Field label="Username or email" error={formState.errors.login?.message}>
        {(a) => <Input {...a} autoComplete="username" {...register('login')} />}
      </Field>
      <Field label="Password" error={formState.errors.password?.message}>
        {(a) => <Input {...a} type="password" autoComplete="current-password" {...register('password')} />}
      </Field>
      <div className="flex justify-end">
        <Link to="/forgot-password" className="text-xs text-muted hover:text-text">Forgot password?</Link>
      </div>
      <Button type="submit" className="w-full" loading={login.isPending}>Log in</Button>
    </form>
  );
}

/** Step two when the account has two-factor authentication on: an authenticator code or a recovery code. */
export function SecondStepForm({
  challenge,
  onSuccess,
  onRestart,
}: {
  challenge: MfaChallenge;
  onSuccess: (me: Me) => void;
  onRestart: (message: string | null) => void;
}) {
  const accept = useAcceptSession();
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const kind = classifyCode(code);
  const valid = recovery ? kind === 'recovery' : kind === 'totp';
  const submit = async () => {
    if (!valid) {
      setError(recovery ? 'Recovery codes look like abcde-fghjk.' : 'Enter the 6-digit code from your authenticator app.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await accountSecurityApi.loginSecondStep(challenge.mfaToken, normaliseCode(code));
      accept(res);
      onSuccess(res.user);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'MFA_TOKEN_EXPIRED') {
        onRestart(e.message);
        return;
      }
      setError(errorMessage(e));
      setCode('');
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      noValidate
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <div>
        <h2 className="text-base font-semibold">Two-factor authentication</h2>
        <p className="mt-1 text-sm text-muted">
          {recovery
            ? 'Enter one of your recovery codes. Each code works once.'
            : 'Enter the 6-digit code from your authenticator app.'}
        </p>
      </div>
      <FormAlert message={error} />
      <Field label={recovery ? 'Recovery code' : 'Authentication code'}>
        {(a) => (
          <Input
            {...a}
            // The code field gets focus as soon as the step appears; it's the only thing to do here.
            // eslint-disable-next-line jsx-a11y/no-autofocus
            autoFocus
            value={code}
            onChange={(e) => setCode(e.target.value)}
            autoComplete="one-time-code"
            inputMode={recovery ? 'text' : 'numeric'}
            spellCheck={false}
            maxLength={recovery ? 14 : 9}
            placeholder={recovery ? 'abcde-fghjk' : '123 456'}
          />
        )}
      </Field>
      <Button type="submit" className="w-full" loading={busy}>Verify</Button>
      <div className="flex flex-wrap justify-between gap-2 text-xs">
        <button
          type="button"
          className="text-accent hover:underline"
          onClick={() => {
            setRecovery((r) => !r);
            setCode('');
            setError(null);
          }}
        >
          {recovery ? 'Use your authenticator app instead' : 'Lost your device? Use a recovery code'}
        </button>
        <button type="button" className="text-muted hover:text-text" onClick={() => onRestart(null)}>
          Start over
        </button>
      </div>
    </form>
  );
}

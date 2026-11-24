import { CircleCheck, CircleX, Clock } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Button, buttonClasses } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';
import { authApi } from '@/features/auth/api';
import { AuthCard } from '@/features/auth/components/AuthCard';
import { useAuth } from '@/features/auth/hooks';
import { outcomeCopy, outcomeFromError, outcomeFromResult, type VerifyOutcome } from '@/features/auth/verifyOutcome';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { errorMessage } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';

type State = 'pending' | 'missing' | VerifyOutcome;

export default function VerifyEmailPage() {
  useDocumentTitle('Verify email');
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [state, setState] = useState<State>(token ? 'pending' : 'missing');
  const ran = useRef(false);

  useEffect(() => {
    if (!token || ran.current) return;
    ran.current = true;
    authApi
      .verifyEmail(token)
      .then((r) => {
        setState(outcomeFromResult(r));
        const u = useAuthStore.getState().user;
        if (u) useAuthStore.getState().setUser({ ...u, emailVerified: true });
      })
      .catch((e: unknown) => setState(outcomeFromError(e)));
  }, [token]);

  const copy =
    state === 'pending'
      ? { title: 'Verifying…', body: '' }
      : state === 'missing'
        ? { title: 'Link is incomplete', body: 'This link is missing its token. Open the link from your email again.' }
        : outcomeCopy[state];
  const good = state === 'verified' || state === 'already-verified';
  const canResend = state === 'expired' || state === 'invalid' || state === 'missing';

  return (
    <AuthCard title={copy.title}>
      <div className="flex flex-col items-center gap-4 text-center text-sm text-muted" aria-live="polite">
        {state === 'pending' && <Spinner size={24} />}
        {good && <CircleCheck size={30} className="text-accent" aria-hidden />}
        {state === 'expired' && <Clock size={30} className="text-warning" aria-hidden />}
        {!good && state !== 'pending' && state !== 'expired' && <CircleX size={30} className="text-danger" aria-hidden />}
        {copy.body && <p>{copy.body}</p>}
        {canResend && <ResendBlock />}
        <Link to="/home" className={buttonClasses(good ? 'primary' : 'outline', 'sm')}>Continue</Link>
      </div>
    </AuthCard>
  );
}

/** Resend needs a session; signed-out visitors are sent to log in first. Limited to 3 per hour by the API. */
function ResendBlock() {
  const { isAuthed, user, status } = useAuth();
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  if (status === 'booting') return null;
  if (!isAuthed) {
    return (
      <p>
        <Link to="/login" className="text-accent underline underline-offset-2 hover:no-underline">Log in</Link> to get a new verification email
        (Settings › Account).
      </p>
    );
  }
  if (user?.emailVerified) return <p>Your current email address is already verified.</p>;
  if (sent) return <p role="status">A new link is on its way to {user?.email}. It expires in 24 hours.</p>;
  return (
    <div className="flex flex-col items-center gap-2">
      <Button
        variant="primary"
        size="sm"
        loading={busy}
        onClick={async () => {
          setBusy(true);
          setErr(null);
          try {
            await authApi.resendVerification();
            setSent(true);
          } catch (e) {
            setErr(errorMessage(e));
          } finally {
            setBusy(false);
          }
        }}
      >
        Send a new verification email
      </Button>
      {err && <p role="alert" className="text-danger">{err}</p>}
    </div>
  );
}

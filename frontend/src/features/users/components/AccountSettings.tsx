import { zodResolver } from '@hookform/resolvers/zod';
import { BlockedUsersCard } from '@/features/moderation/components/BlockControls';
import { Laptop, MailWarning } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { Button } from '@/components/ui/Button';
import { Badge, Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { Dialog } from '@/components/ui/Dialog';
import { Field, FormAlert, Input } from '@/components/ui/Field';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { authApi } from '@/features/auth/api';
import { useRevokeSession, useSessions } from '@/features/auth/hooks';
import { errorMessage } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import type { Me } from '@/types/api';
import { timeAgo } from '@/utils/format';
import { applyServerErrors } from '@/utils/forms';
import { passwordSchema } from '@/utils/validation';
import { usersApi } from '../api';
import { useChangePassword } from '../hooks';
import { useQueryClient } from '@tanstack/react-query';
import { SecuritySettings } from '@/features/accountSecurity/components/SecuritySettings';
import { useRevokeOtherSessions } from '@/features/accountSecurity/hooks';
import { authMethodLabel, isMultiFactor } from '@/features/accountSecurity/logic';

const pwSchema = z
  .object({ currentPassword: z.string().min(1, 'Enter your current password'), newPassword: passwordSchema, confirm: z.string() })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: 'Passwords do not match' });
type PwValues = z.infer<typeof pwSchema>;

function PasswordCard() {
  const change = useChangePassword();
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, setError, reset, formState } = useForm<PwValues>({ resolver: zodResolver(pwSchema) });
  return (
    <Card>
      <CardHeader title="Password" />
      <form
        noValidate
        className="space-y-4 p-4"
        onSubmit={handleSubmit((v) => {
          setFormError(null);
          change.mutate(
            { currentPassword: v.currentPassword, newPassword: v.newPassword },
            {
              onSuccess: () => {
                reset();
                toast.success('Password changed. Other sessions were signed out.');
              },
              onError: (e) => setFormError(applyServerErrors(e, setError, ['currentPassword', 'newPassword'])),
            },
          );
        })}
      >
        <FormAlert message={formError} />
        <Field label="Current password" error={formState.errors.currentPassword?.message}>{(a) => <Input {...a} type="password" autoComplete="current-password" {...register('currentPassword')} />}</Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="New password" error={formState.errors.newPassword?.message}>{(a) => <Input {...a} type="password" autoComplete="new-password" {...register('newPassword')} />}</Field>
          <Field label="Confirm new password" error={formState.errors.confirm?.message}>{(a) => <Input {...a} type="password" autoComplete="new-password" {...register('confirm')} />}</Field>
        </div>
        <div className="flex justify-end"><Button type="submit" loading={change.isPending}>Change password</Button></div>
      </form>
    </Card>
  );
}

function SessionsCard() {
  const q = useSessions();
  const revoke = useRevokeSession();
  const others = useRevokeOtherSessions();
  const hasOthers = (q.data ?? []).some((s) => !s.current);
  return (
    <Card>
      <CardHeader
        title="Active sessions"
        action={
          hasOthers ? (
            <Button
              variant="outline"
              size="sm"
              loading={others.isPending}
              onClick={() =>
                others.mutate(undefined, {
                  onSuccess: (r) => toast.success(r.revoked === 1 ? 'Signed out 1 other session' : `Signed out ${r.revoked} other sessions`),
                  onError: (e) => toast.error(errorMessage(e)),
                })
              }
            >
              Sign out everywhere else
            </Button>
          ) : undefined
        }
      />
      {q.isPending && <div className="space-y-2 p-4"><Skeleton className="h-10" /><Skeleton className="h-10" /></div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && (
        <ul className="divide-y divide-border">
          {q.data.map((s) => (
            <li key={s.id} className="flex items-center gap-3 px-4 py-3">
              <Laptop size={18} className="shrink-0 text-muted" />
              <div className="min-w-0 flex-1">
                <div className="flex min-w-0 items-center gap-2">
                  <span className="truncate text-sm">{s.userAgent ?? 'Unknown device'}</span>
                  {authMethodLabel(s.authMethod) && (
                    <Badge tone={isMultiFactor(s.authMethod) ? 'info' : 'neutral'}>{authMethodLabel(s.authMethod)}</Badge>
                  )}
                </div>
                <div className="text-xs text-faint">
                  {s.ip ?? 'unknown IP'} · signed in {timeAgo(s.createdAt)} ago{s.lastUsedAt ? ` · active ${timeAgo(s.lastUsedAt)}` : ''}
                </div>
              </div>
              {s.current ? (
                <Badge tone="accent">This device</Badge>
              ) : (
                <Button variant="outline" size="sm" loading={revoke.isPending && revoke.variables === s.id} onClick={() => revoke.mutate(s.id, { onSuccess: () => toast.success('Session revoked'), onError: (e) => toast.error(errorMessage(e)) })}>
                  Revoke
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function DangerCard() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [mode, setMode] = useState<'deactivate' | 'delete' | null>(null);
  const [password, setPassword] = useState('');
  const [confirmText, setConfirmText] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const close = () => {
    setMode(null);
    setPassword('');
    setConfirmText('');
    setErr(null);
  };
  const run = async () => {
    setBusy(true);
    setErr(null);
    try {
      if (mode === 'delete') await usersApi.deleteAccount(password);
      else await usersApi.deactivate(password);
      useAuthStore.getState().clear();
      qc.clear();
      toast.info(mode === 'delete' ? 'Your account was deleted' : 'Your account is deactivated. Log in again to reactivate.');
      navigate('/');
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card className="border-danger/40">
      <CardHeader title="Danger zone" />
      <div className="space-y-4 p-4 text-sm">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="font-medium">Deactivate account</div>
            <p className="text-muted">Hide your profile and sign out everywhere. Log in again to come back.</p>
          </div>
          <Button variant="outline" size="sm" onClick={() => setMode('deactivate')}>Deactivate</Button>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
          <div>
            <div className="font-medium text-danger">Delete account</div>
            <p className="text-muted">Permanently anonymize your account. Your posts and comments remain as [deleted]. This can’t be undone.</p>
          </div>
          <Button variant="danger" size="sm" onClick={() => setMode('delete')}>Delete account</Button>
        </div>
      </div>
      <Dialog
        open={mode !== null}
        onOpenChange={(o) => !o && close()}
        size="sm"
        title={mode === 'delete' ? 'Delete your account?' : 'Deactivate your account?'}
        description="Confirm with your password."
        footer={
          <>
            <Button variant="ghost" onClick={close}>Cancel</Button>
            <Button variant={mode === 'delete' ? 'danger' : 'primary'} loading={busy} disabled={!password || (mode === 'delete' && confirmText !== 'DELETE')} onClick={run}>
              {mode === 'delete' ? 'Delete permanently' : 'Deactivate'}
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <FormAlert message={err} />
          <Field label="Password">{(a) => <Input {...a} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
          {mode === 'delete' && (
            <Field label="Type DELETE to confirm">{(a) => <Input {...a} value={confirmText} onChange={(e) => setConfirmText(e.target.value)} />}</Field>
          )}
        </div>
      </Dialog>
    </Card>
  );
}

function EmailCard({ me }: { me: Me }) {
  const [sending, setSending] = useState(false);
  return (
    <Card>
      <CardHeader title="Email" />
      <div className="flex flex-wrap items-center justify-between gap-3 p-4 text-sm">
        <div>
          <div className="font-medium">{me.email}</div>
          {me.emailVerified ? <Badge tone="accent">Verified</Badge> : <span className="inline-flex items-center gap-1.5 text-warning"><MailWarning size={14} /> Not verified</span>}
        </div>
        {!me.emailVerified && (
          <Button
            variant="outline"
            size="sm"
            loading={sending}
            onClick={async () => {
              setSending(true);
              try {
                await authApi.resendVerification();
                toast.success('Verification email sent');
              } catch (e) {
                toast.error(errorMessage(e));
              } finally {
                setSending(false);
              }
            }}
          >
            Resend verification
          </Button>
        )}
      </div>
    </Card>
  );
}

export function AccountSettings({ me }: { me: Me }) {
  // Emails link to #sessions; the card renders after the route, so scroll once it exists.
  useEffect(() => {
    if (window.location.hash !== '#sessions') return;
    const t = window.setTimeout(() => document.getElementById('sessions')?.scrollIntoView({ block: 'start' }), 300);
    return () => window.clearTimeout(t);
  }, []);
  return (
    <div className="space-y-4">
      <EmailCard me={me} />
      <PasswordCard />
      <SecuritySettings me={me} />
      {/* Target of the "Review sessions" link in new-sign-in emails. */}
      <div id="sessions" className="scroll-mt-20">
        <SessionsCard />
      </div>
      <BlockedUsersCard />
      <DangerCard />
    </div>
  );
}

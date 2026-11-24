import { AlertTriangle, Copy, Download, KeyRound, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/Button';
import { Badge, Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { Dialog } from '@/components/ui/Dialog';
import { Field, FormAlert, Input } from '@/components/ui/Field';
import { ErrorState } from '@/components/ui/States';
import { Switch } from '@/components/ui/Switch';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { Me } from '@/types/api';
import type { PasskeyInfo, TotpSetup } from '@/types/accountSecurity';
import { timeAgo } from '@/utils/format';
import { accountSecurityApi } from '../api';
import {
  securityKeys,
  useAddPasskey,
  useDeletePasskey,
  usePasskeys,
  useRenamePasskey,
  useSecuritySummary,
  useSetAlerts,
} from '../hooks';
import { classifyCode, groupSecret, normaliseCode, recoveryCodesText, svgDataUrl } from '../logic';
import { passkeySupport, supportMessage, webauthnErrorMessage } from '../webauthn';

function RecoveryCodesList({ codes, username }: { codes: string[]; username: string }) {
  const text = recoveryCodesText(codes, username);
  return (
    <div className="space-y-3">
      <p className="text-sm text-muted">
        Save these somewhere safe, like a password manager. Each one signs you in once if you lose your authenticator.
        <strong className="text-text"> You won’t see them again.</strong>
      </p>
      <ol className="grid grid-cols-2 gap-x-6 gap-y-1 rounded-md border border-border bg-surface-2 p-3 font-mono text-sm">
        {codes.map((c) => <li key={c}>{c}</li>)}
      </ol>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(text);
              toast.success('Recovery codes copied');
            } catch {
              toast.error('Couldn’t copy. Select the codes and copy them by hand.');
            }
          }}
        >
          <Copy size={14} /> Copy
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
            const a = document.createElement('a');
            a.href = url;
            a.download = 'howlers-recovery-codes.txt';
            a.click();
            URL.revokeObjectURL(url);
          }}
        >
          <Download size={14} /> Download
        </Button>
      </div>
    </div>
  );
}

type Step = { kind: 'password' } | { kind: 'scan'; setup: TotpSetup } | { kind: 'codes'; codes: string[] };

function EnableTotpDialog({ open, onClose, me }: { open: boolean; onClose: () => void; me: Me }) {
  const qc = useQueryClient();
  const [step, setStep] = useState<Step>({ kind: 'password' });
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const close = () => {
    setStep({ kind: 'password' });
    setPassword('');
    setCode('');
    setErr(null);
    onClose();
  };
  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setErr(null);
    try {
      await fn();
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  const footer =
    step.kind === 'password' ? (
      <>
        <Button variant="ghost" onClick={close}>Cancel</Button>
        <Button loading={busy} disabled={!password} onClick={() => run(async () => setStep({ kind: 'scan', setup: await accountSecurityApi.totpSetup(password) }))}>
          Continue
        </Button>
      </>
    ) : step.kind === 'scan' ? (
      <>
        <Button variant="ghost" onClick={close}>Cancel</Button>
        <Button
          loading={busy}
          disabled={classifyCode(code) !== 'totp'}
          onClick={() =>
            run(async () => {
              const r = await accountSecurityApi.totpConfirm(normaliseCode(code));
              setStep({ kind: 'codes', codes: r.recoveryCodes });
              qc.invalidateQueries({ queryKey: securityKeys.summary });
            })
          }
        >
          Turn on
        </Button>
      </>
    ) : (
      <Button onClick={close}>I’ve saved my codes</Button>
    );
  return (
    <Dialog
      open={open}
      // The recovery codes are shown once; closing by accident would lose them, so that step only closes by its button.
      onOpenChange={(o) => !o && step.kind !== 'codes' && close()}
      title={step.kind === 'codes' ? 'Two-factor authentication is on' : 'Turn on two-factor authentication'}
      description={
        step.kind === 'password'
          ? 'Confirm your password to start.'
          : step.kind === 'scan'
            ? 'Scan the QR code with an authenticator app (such as 2FAS, Aegis, Google Authenticator, Microsoft Authenticator or 1Password), then enter the 6-digit code it shows.'
            : undefined
      }
      footer={footer}
    >
      <div className="space-y-4">
        <FormAlert message={err} />
        {step.kind === 'password' && (
          <Field label="Password">{(a) => <Input {...a} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
        )}
        {step.kind === 'scan' && (
          <>
            <div className="flex justify-center">
              <img src={svgDataUrl(step.setup.qrSvg)} alt="QR code for your authenticator app" width={200} height={200} className="rounded-md bg-white p-2" />
            </div>
            <details className="text-sm">
              <summary className="cursor-pointer text-muted">Can’t scan? Enter the key by hand</summary>
              <p className="mt-2 break-all font-mono text-sm" aria-label="Setup key">{groupSecret(step.setup.secret)}</p>
              <p className="mt-1 text-xs text-muted">Time-based, 6 digits, every 30 seconds.</p>
            </details>
            <Field label="Code from the app">
              {(a) => <Input {...a} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" inputMode="numeric" maxLength={9} placeholder="123 456" />}
            </Field>
          </>
        )}
        {step.kind === 'codes' && <RecoveryCodesList codes={step.codes} username={me.username} />}
      </div>
    </Dialog>
  );
}

function CodeDialog({
  open,
  onClose,
  title,
  description,
  withPassword,
  allowRecovery,
  confirmLabel,
  danger,
  onSubmit,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description: string;
  withPassword: boolean;
  allowRecovery: boolean;
  confirmLabel: string;
  danger?: boolean;
  onSubmit: (password: string, code: string) => Promise<void>;
}) {
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const kind = classifyCode(code);
  const ok = (kind === 'totp' || (allowRecovery && kind === 'recovery')) && (!withPassword || password.length > 0);
  const close = () => {
    setPassword('');
    setCode('');
    setErr(null);
    onClose();
  };
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && close()}
      size="sm"
      title={title}
      description={description}
      footer={
        <>
          <Button variant="ghost" onClick={close}>Cancel</Button>
          <Button
            variant={danger ? 'danger' : 'primary'}
            loading={busy}
            disabled={!ok}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                await onSubmit(password, normaliseCode(code));
                setPassword('');
                setCode('');
              } catch (e) {
                setErr(errorMessage(e));
              } finally {
                setBusy(false);
              }
            }}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <FormAlert message={err} />
        {withPassword && (
          <Field label="Password">{(a) => <Input {...a} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
        )}
        <Field label={allowRecovery ? 'Authenticator code or recovery code' : 'Authenticator code'}>
          {(a) => <Input {...a} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" spellCheck={false} maxLength={14} />}
        </Field>
      </div>
    </Dialog>
  );
}

function TwoFactorCard({ me }: { me: Me }) {
  const q = useSecuritySummary();
  const qc = useQueryClient();
  const [dialog, setDialog] = useState<'enable' | 'disable' | 'regenerate' | null>(null);
  const [fresh, setFresh] = useState<string[] | null>(null);
  const t = q.data?.totp;
  const refresh = () => qc.invalidateQueries({ queryKey: securityKeys.summary });
  return (
    <Card>
      <CardHeader title="Two-factor authentication" />
      {q.isPending && <div className="p-4"><Skeleton className="h-10" /></div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {t && (
        <div className="space-y-3 p-4 text-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <div className="flex items-center gap-2 font-medium">
                Authenticator app {t.enabled ? <Badge tone="accent">On</Badge> : <Badge>Off</Badge>}
              </div>
              <p className="text-muted">
                {t.enabled
                  ? `After your password, you’ll enter a code from your app. On since ${new Date(t.enabledAt ?? '').toLocaleDateString()}.`
                  : 'Ask for a 6-digit code from an authenticator app after your password. Signing in with a passkey already counts as two factors.'}
              </p>
            </div>
            {t.enabled ? (
              <Button variant="outline" size="sm" onClick={() => setDialog('disable')}>Turn off</Button>
            ) : (
              <Button size="sm" onClick={() => setDialog('enable')}>Turn on</Button>
            )}
          </div>
          {t.enabled && (
            <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
              <div className={t.recoveryCodesRemaining <= 2 ? 'text-warning' : 'text-muted'}>
                {t.recoveryCodesRemaining <= 2 && <AlertTriangle size={14} className="mr-1 inline" />}
                {t.recoveryCodesRemaining} of 10 recovery codes left
              </div>
              <Button variant="outline" size="sm" onClick={() => setDialog('regenerate')}>New recovery codes</Button>
            </div>
          )}
          {fresh && (
            <div className="border-t border-border pt-3">
              <RecoveryCodesList codes={fresh} username={me.username} />
              <Button className="mt-3" size="sm" variant="ghost" onClick={() => setFresh(null)}>Hide codes</Button>
            </div>
          )}
        </div>
      )}
      <EnableTotpDialog open={dialog === 'enable'} onClose={() => setDialog(null)} me={me} />
      <CodeDialog
        open={dialog === 'disable'}
        onClose={() => setDialog(null)}
        title="Turn off two-factor authentication?"
        description="Your password alone will be enough to sign in. Confirm with your password and a code."
        withPassword
        allowRecovery
        danger
        confirmLabel="Turn off"
        onSubmit={async (password, code) => {
          await accountSecurityApi.totpDisable(password, code);
          setDialog(null);
          setFresh(null);
          refresh();
          toast.success('Two-factor authentication is off');
        }}
      />
      <CodeDialog
        open={dialog === 'regenerate'}
        onClose={() => setDialog(null)}
        title="Get new recovery codes?"
        description="Your old recovery codes stop working. Enter a code from your authenticator app."
        withPassword={false}
        allowRecovery={false}
        confirmLabel="Create new codes"
        onSubmit={async (_p, code) => {
          const r = await accountSecurityApi.regenerateRecoveryCodes(code);
          setDialog(null);
          setFresh(r.recoveryCodes);
          refresh();
        }}
      />
    </Card>
  );
}

function PasskeyRow({ p }: { p: PasskeyInfo }) {
  const rename = useRenamePasskey();
  const del = useDeletePasskey();
  const [mode, setMode] = useState<'rename' | 'delete' | null>(null);
  const [name, setName] = useState(p.name);
  return (
    <li className="flex flex-wrap items-center gap-3 px-4 py-3">
      <KeyRound size={18} className="shrink-0 text-muted" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="truncate font-medium">{p.name}</span>
          {p.synced && <Badge tone="info">Synced</Badge>}
          {p.flagged && <Badge tone="danger">Blocked</Badge>}
        </div>
        <div className="text-xs text-faint">
          Added {timeAgo(p.createdAt)} ago · {p.lastUsedAt ? `last used ${timeAgo(p.lastUsedAt)} ago` : 'not used yet'}
        </div>
        {p.flagged && (
          <p className="mt-1 text-xs text-danger">
            This passkey sent an unexpected signature counter, which can mean it was copied. It can’t sign in. Remove it, and add it again if it’s yours.
          </p>
        )}
      </div>
      <div className="flex gap-2">
        <Button variant="ghost" size="sm" onClick={() => setMode('rename')}>Rename</Button>
        <Button variant="outline" size="sm" onClick={() => setMode('delete')}>Remove</Button>
      </div>
      <Dialog
        open={mode === 'rename'}
        onOpenChange={(o) => !o && setMode(null)}
        size="sm"
        title="Rename passkey"
        footer={
          <>
            <Button variant="ghost" onClick={() => setMode(null)}>Cancel</Button>
            <Button
              loading={rename.isPending}
              disabled={!name.trim()}
              onClick={() => rename.mutate({ id: p.id, name: name.trim() }, { onSuccess: () => setMode(null), onError: (e) => toast.error(errorMessage(e)) })}
            >
              Save
            </Button>
          </>
        }
      >
        <Field label="Name">{(a) => <Input {...a} value={name} maxLength={60} onChange={(e) => setName(e.target.value)} />}</Field>
      </Dialog>
      <Dialog
        open={mode === 'delete'}
        onOpenChange={(o) => !o && setMode(null)}
        size="sm"
        title="Remove this passkey?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setMode(null)}>Cancel</Button>
            <Button
              variant="danger"
              loading={del.isPending}
              onClick={() => del.mutate(p.id, { onSuccess: () => { setMode(null); toast.success('Passkey removed'); }, onError: (e) => toast.error(errorMessage(e)) })}
            >
              Remove
            </Button>
          </>
        }
      >
        <p className="text-sm text-muted">
          “{p.name}” will no longer sign you in. The passkey may stay on the device; you can delete it there too.
        </p>
      </Dialog>
    </li>
  );
}

function PasskeysCard() {
  const q = usePasskeys();
  const add = useAddPasskey();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const support = passkeySupport();
  const unsupported = supportMessage(support);
  return (
    <Card>
      <CardHeader
        title="Passkeys"
        action={<Button size="sm" disabled={support !== 'supported'} onClick={() => setOpen(true)}>Add a passkey</Button>}
      />
      <p className="px-4 pt-3 text-sm text-muted">
        Sign in with your fingerprint, face or device PIN instead of a password. Passkeys can’t be phished and count as
        two-factor authentication on their own.
      </p>
      {unsupported && <p className="px-4 pt-2 text-sm text-warning">{unsupported}</p>}
      {q.isPending && <div className="p-4"><Skeleton className="h-10" /></div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.length === 0 && <p className="px-4 py-3 text-sm text-faint">No passkeys yet.</p>}
      {q.data && q.data.length > 0 && (
        <ul className="mt-2 divide-y divide-border border-t border-border">
          {q.data.map((p) => <PasskeyRow key={p.id} p={p} />)}
        </ul>
      )}
      {!q.data?.length && <div className="pb-1" />}
      <Dialog
        open={open}
        onOpenChange={(o) => {
          if (!o) {
            setOpen(false);
            setErr(null);
            setName('');
          }
        }}
        size="sm"
        title="Add a passkey"
        description="Your browser or device will ask you to confirm with a fingerprint, face or PIN. Only a public key is sent to Howlers."
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button
              loading={add.isPending}
              onClick={() => {
                setErr(null);
                add.mutate(name.trim() || undefined, {
                  onSuccess: () => {
                    setOpen(false);
                    setName('');
                    toast.success('Passkey added');
                  },
                  onError: (e) => setErr(webauthnErrorMessage(e, 'register')),
                });
              }}
            >
              Create passkey
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <FormAlert message={err} />
          <Field label="Name" optional hint="So you can tell your passkeys apart, e.g. “Work laptop”. Leave blank to name it after this browser.">
            {(a) => <Input {...a} value={name} maxLength={60} onChange={(e) => setName(e.target.value)} />}
          </Field>
        </div>
      </Dialog>
    </Card>
  );
}

function SignInAlertsCard() {
  const q = useSecuritySummary();
  const set = useSetAlerts();
  return (
    <Card>
      <CardHeader title="Sign-in alerts" />
      <div className="px-4">
        {q.data ? (
          <Switch
            label="Email me about new sign-ins"
            description="When your account is signed in to from a browser or device it hasn’t seen before, we email you the time and browser. Not sent for the sign-in right after you register."
            checked={q.data.newSignInAlerts}
            disabled={set.isPending}
            onCheckedChange={(v) =>
              set.mutate(v, { onSuccess: () => toast.success(v ? 'Sign-in alerts on' : 'Sign-in alerts off'), onError: (e) => toast.error(errorMessage(e)) })
            }
          />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        ) : (
          <div className="py-3"><Skeleton className="h-8" /></div>
        )}
      </div>
    </Card>
  );
}

export function SecuritySettings({ me }: { me: Me }) {
  return (
    <>
      <div className="flex items-center gap-2 px-1 text-sm text-muted">
        <ShieldCheck size={16} /> Signing in
      </div>
      <PasskeysCard />
      <TwoFactorCard me={me} />
      <SignInAlertsCard />
    </>
  );
}

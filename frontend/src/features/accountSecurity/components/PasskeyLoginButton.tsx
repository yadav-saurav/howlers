import { KeyRound } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { FormAlert } from '@/components/ui/Field';
import type { Me } from '@/types/api';
import { signInWithPasskey, useAcceptSession } from '../hooks';
import { passkeySupport, supportMessage, webauthnErrorMessage } from '../webauthn';

/**
 * "Sign in with a passkey": no username needed, the device offers the passkeys it holds for Howlers. A passkey
 * sign-in counts as two-factor (the device checks a PIN or biometric), so there's no code step after it.
 */
export function PasskeyLoginButton({ onSuccess }: { onSuccess: (me: Me) => void }) {
  const accept = useAcceptSession();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const support = passkeySupport();
  const unsupported = supportMessage(support);
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-3 text-xs text-faint" aria-hidden="true">
        <span className="h-px flex-1 bg-border" /> or <span className="h-px flex-1 bg-border" />
      </div>
      <FormAlert message={error} />
      <Button
        type="button"
        variant="outline"
        className="w-full"
        loading={busy}
        disabled={support !== 'supported'}
        aria-describedby={unsupported ? 'passkey-unsupported' : undefined}
        onClick={async () => {
          setBusy(true);
          setError(null);
          try {
            const res = await signInWithPasskey();
            accept(res);
            onSuccess(res.user);
          } catch (e) {
            setError(webauthnErrorMessage(e, 'login'));
          } finally {
            setBusy(false);
          }
        }}
      >
        <KeyRound size={16} /> Sign in with a passkey
      </Button>
      {unsupported && <p id="passkey-unsupported" className="text-center text-xs text-muted">{unsupported}</p>}
    </div>
  );
}

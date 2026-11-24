import { Check, Copy, KeyRound, Link2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Field, FormAlert, Input, Select } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { FileItem, KeyMode } from '@/types/files';
import { cn } from '@/utils/format';
import { fetchOwnContent, filesApi, useFileMutation } from '../api';
import { decryptMeta, importKey, parseKeyInput, wrapKey } from '../crypto';
import { EXPIRY_PRESETS, expiryFromPreset, formatBytes, passphraseProblem, scanSummary, shareUrl, type ExpiryPreset } from '../helpers';
import { keyStore } from '../keyStore';
import { E2eeNote } from './E2eeNote';

function CopyField({ label, value, hint }: { label: string; value: string; hint?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div>
      <div className="mb-1.5 text-sm font-medium">{label}</div>
      <div className="flex gap-2">
        <Input readOnly value={value} onFocus={(e) => e.currentTarget.select()} className="font-mono text-xs" aria-label={label} />
        <Button
          variant="secondary"
          onClick={() =>
            navigator.clipboard.writeText(value).then(
              () => {
                setCopied(true);
                window.setTimeout(() => setCopied(false), 2000);
              },
              () => toast.error('Could not copy. Select the text and copy it manually.'),
            )
          }
        >
          {copied ? <Check size={14} /> : <Copy size={14} />} {copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
      {hint && <p className="mt-1.5 text-xs text-faint">{hint}</p>}
    </div>
  );
}

/**
 * Creates a share link: optional expiry, password and download limit. For E2EE files the owner also picks where the
 * key goes: in the link's #fragment (never sent to the server) or wrapped with a passphrase in this browser.
 * The full link is shown once; the server stores only a hash of its token.
 */
export function ShareDialog({
  file,
  initialKey,
  open,
  onOpenChange,
}: {
  file: FileItem;
  initialKey?: string | null;
  open: boolean;
  onOpenChange: (v: boolean) => void;
}) {
  const [expiry, setExpiry] = useState<ExpiryPreset>('7d');
  const [password, setPassword] = useState('');
  const [maxDownloads, setMaxDownloads] = useState('');
  const [keyMode, setKeyMode] = useState<KeyMode>('FRAGMENT');
  const [passphrase, setPassphrase] = useState('');
  const [confirm, setConfirm] = useState('');
  const [keyText, setKeyText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ url: string; keyMode: KeyMode | null } | null>(null);
  const [plainName, setPlainName] = useState<string | null>(null);
  const known = initialKey ?? (file.e2ee ? keyStore.get(file.id) : null);
  const create = useFileMutation((body: Parameters<typeof filesApi.createLink>[1]) => filesApi.createLink(file.id, body));

  useEffect(() => {
    if (!open) {
      setResult(null);
      setError(null);
      setPassword('');
      setPassphrase('');
      setConfirm('');
      setKeyText('');
    }
  }, [open]);

  // For E2EE files with a known key, show the real name (decrypted locally from the first bytes).
  useEffect(() => {
    let live = true;
    setPlainName(null);
    if (!open || !file.e2ee || !known) return;
    (async () => {
      try {
        const head = await fetchOwnContent(file.id, undefined, 'bytes=0-70000');
        const { meta } = await decryptMeta(head, await importKey(known));
        if (live) setPlainName(meta.name);
      } catch {
        /* the key may be wrong; creating a link re-checks */
      }
    })();
    return () => {
      live = false;
    };
  }, [open, file.e2ee, file.id, known]);

  async function submit() {
    setError(null);
    const max = maxDownloads.trim() ? Number(maxDownloads) : null;
    if (max != null && (!Number.isInteger(max) || max < 1 || max > 10_000)) {
      setError('Download limit must be a whole number from 1 to 10,000.');
      return;
    }
    if (password && password.length < 4) {
      setError('Passwords need at least 4 characters.');
      return;
    }
    setBusy(true);
    try {
      let key: string | null = null;
      let wrappedKey = null;
      if (file.e2ee) {
        key = known ?? parseKeyInput(keyText);
        if (!key) throw new Error('Paste the file key, or a full link to this file that ends in #k=…');
        const cryptoKey = await importKey(key);
        // Make sure the key really opens this file before handing it to anyone.
        const head = await fetchOwnContent(file.id, undefined, 'bytes=0-70000');
        await decryptMeta(head, cryptoKey);
        if (!known) keyStore.set(file.id, key);
        if (keyMode === 'PASSPHRASE') {
          const problem = passphraseProblem(passphrase, confirm);
          if (problem) throw new Error(problem);
          wrappedKey = await wrapKey(cryptoKey, passphrase);
        }
      }
      const created = await create.mutateAsync({
        expiresAt: expiryFromPreset(expiry),
        password: password || null,
        maxDownloads: max,
        keyMode: file.e2ee ? keyMode : null,
        wrappedKey,
      });
      setResult({
        url: shareUrl(window.location.origin, created.token, file.e2ee && keyMode === 'FRAGMENT' ? key : null),
        keyMode: created.link.keyMode,
      });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={result ? 'Link ready' : 'Share a link'}
      description={plainName ?? file.name}
      footer={
        result ? (
          <Button onClick={() => onOpenChange(false)}>Done</Button>
        ) : (
          <>
            <Button variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button onClick={submit} loading={busy}>
              <Link2 size={14} /> Create link
            </Button>
          </>
        )
      }
    >
      {result ? (
        <div className="space-y-4">
          <CopyField
            label="Share link"
            value={result.url}
            hint={
              result.keyMode === 'FRAGMENT'
                ? 'The decryption key is after the # in this link. Browsers never send that part to our server, but anyone with the whole link can open the file.'
                : 'Anyone with this link can open the share page. Copy it now: we only store a fingerprint of it, so it can’t be shown again.'
            }
          />
          {result.keyMode === 'PASSPHRASE' && (
            <p className="flex gap-2 rounded-lg bg-surface-2 p-3 text-xs text-muted">
              <KeyRound size={14} className="mt-0.5 shrink-0" aria-hidden /> Send the passphrase through a different channel (for example, say it in
              person or by phone). Without it, the link is useless; with both, the file can be decrypted.
            </p>
          )}
          {initialKey && (
            <CopyField
              label="Recovery key"
              value={initialKey}
              hint="Keep this somewhere safe (a password manager works). It is the only way to decrypt this file if you lose your links and this browser's memory."
            />
          )}
        </div>
      ) : (
        <div className="space-y-4">
          {file.e2ee && <E2eeNote compact />}
          {initialKey && (
            <CopyField
              label="Recovery key for this file"
              value={initialKey}
              hint="Save it now, even if you don't share the file yet. Without the key, nobody (including you and us) can decrypt it."
            />
          )}
          {file.e2ee && !known && (
            <Field label="File key" hint="Paste the recovery key, or any earlier full link to this file (the part after #k= is the key).">
              {(p) => <Input {...p} value={keyText} onChange={(e) => setKeyText(e.target.value)} autoComplete="off" spellCheck={false} />}
            </Field>
          )}
          {file.e2ee && (
            <fieldset>
              <legend className="mb-1.5 text-sm font-medium">How does the recipient get the key?</legend>
              <div className="grid gap-2 sm:grid-cols-2">
                {(
                  [
                    ['FRAGMENT', 'In the link', 'One link is enough. Anyone with the full link can decrypt.'],
                    ['PASSPHRASE', 'With a passphrase', 'The key is wrapped with a passphrase you send separately.'],
                  ] as const
                ).map(([value, title, desc]) => (
                  <label
                    key={value}
                    className={cn(
                      'cursor-pointer rounded-lg border p-3 text-sm',
                      keyMode === value ? 'border-brand bg-brand/5' : 'border-border hover:bg-surface-2',
                    )}
                  >
                    <input type="radio" name="keyMode" value={value} checked={keyMode === value} onChange={() => setKeyMode(value)} className="sr-only" />
                    <span className="block font-medium">{title}</span>
                    <span className="mt-0.5 block text-xs text-muted">{desc}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          )}
          {file.e2ee && keyMode === 'PASSPHRASE' && (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Passphrase" hint="At least 10 characters. Derived with PBKDF2-SHA256, 600,000 iterations.">
                {(p) => <Input {...p} type="password" value={passphrase} onChange={(e) => setPassphrase(e.target.value)} autoComplete="new-password" />}
              </Field>
              <Field label="Repeat passphrase">
                {(p) => <Input {...p} type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" />}
              </Field>
            </div>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Expires after">
              {(p) => (
                <Select {...p} value={expiry} onChange={(e) => setExpiry(e.target.value as ExpiryPreset)}>
                  {EXPIRY_PRESETS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <Field label="Download limit" optional hint="Each counted download is a full copy.">
              {(p) => <Input {...p} inputMode="numeric" placeholder="Unlimited" value={maxDownloads} onChange={(e) => setMaxDownloads(e.target.value.replace(/[^0-9]/g, ''))} />}
            </Field>
          </div>
          <Field
            label="Access password"
            optional
            hint={file.e2ee ? 'Checked by the server before it sends the ciphertext. Separate from the passphrase.' : 'Checked by the server. Wrong guesses are limited and logged.'}
          >
            {(p) => <Input {...p} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" maxLength={128} />}
          </Field>
          {!file.e2ee && file.scanStatus === 'CLEAN' && (
            <p className="text-xs text-faint">
              {formatBytes(file.sizeBytes)} · {scanSummary(file.scanEngine).text}
            </p>
          )}
          <FormAlert message={error} />
        </div>
      )}
    </Dialog>
  );
}

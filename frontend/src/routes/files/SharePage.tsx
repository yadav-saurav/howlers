import { Clock, Download, FileText, KeyRound, Link2Off, LockKeyhole, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { Field, FormAlert, Input } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { fetchDownload, filesApi, openDownloadResponse, saveBlob, triggerDownload, useShareMeta } from '@/features/files/api';
import { E2eeNote } from '@/features/files/components/E2eeNote';
import { MemoryWarning, StreamingSavePrompt } from '@/features/files/components/StreamingSave';
import { DecryptionError, decryptFile, importKey, keyFromFragment, safeFileName, unwrapKey } from '@/features/files/crypto';
import { formatBytes, scanSummary, typeLabel } from '@/features/files/helpers';
import { STREAM_THRESHOLD_BYTES, canStreamToDisk, openContainerStream, responseBytes, type OpenedContainer } from '@/features/files/streamDecrypt';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { ApiError, errorMessage } from '@/services/apiError';

/**
 * Public share page. Works signed out. Plain files download through a short-lived signed URL; end-to-end encrypted
 * files are fetched as ciphertext and decrypted here, with the key from the URL #fragment or a passphrase.
 */
export default function SharePage() {
  const { token = '' } = useParams();
  const { hash } = useLocation();
  const meta = useShareMeta(token);
  const [password, setPassword] = useState('');
  const [passphrase, setPassphrase] = useState('');
  const [fieldError, setFieldError] = useState<{ password?: string; passphrase?: string }>({});
  const [error, setError] = useState<string | null>(null);
  const [phase, setPhase] = useState<'idle' | 'unlocking' | 'downloading' | 'decrypting' | 'ready' | 'done'>('idle');
  const [opened, setOpened] = useState<OpenedContainer | null>(null);
  const [progress, setProgress] = useState(0);
  const [savedName, setSavedName] = useState<string | null>(null);
  useDocumentTitle(meta.data?.fileName ?? 'Shared file');

  const m = meta.data;
  const fragmentKey = keyFromFragment(hash);
  const missingKey = !!m && m.e2ee && m.keyMode === 'FRAGMENT' && !fragmentKey;

  async function start() {
    if (!m) return;
    setError(null);
    setFieldError({});
    if (m.requiresPassword && !password) {
      setFieldError({ password: 'Enter the password you were given.' });
      return;
    }
    if (m.e2ee && m.keyMode === 'PASSPHRASE' && !passphrase) {
      setFieldError({ passphrase: 'Enter the passphrase you were given.' });
      return;
    }
    setPhase('unlocking');
    try {
      const unlock = await filesApi.unlock(token, password || undefined);
      if (!m.e2ee) {
        triggerDownload(unlock.downloadUrl);
        setPhase('done');
      } else {
        // Get the key first, so a wrong passphrase never uses up a counted download.
        let key: CryptoKey;
        if (m.keyMode === 'PASSPHRASE') {
          if (!unlock.wrappedKey) throw new Error('This link has no key to unwrap.');
          try {
            key = await unwrapKey(unlock.wrappedKey, passphrase);
          } catch (e) {
            if (e instanceof DecryptionError) {
              setFieldError({ passphrase: e.message });
              setPhase('idle');
              return;
            }
            throw e;
          }
        } else {
          key = await importKey(fragmentKey!);
        }
        setPhase('downloading');
        setProgress(0);
        if (canStreamToDisk() && m.sizeBytes >= STREAM_THRESHOLD_BYTES) {
          // Large file: decrypt as it downloads, straight to a file the user picks (needs one more click).
          const res = await openDownloadResponse(unlock.downloadUrl);
          setOpened(await openContainerStream(responseBytes(res), key));
          setPhase('ready');
          void meta.refetch();
          return;
        }
        const container = await fetchDownload(unlock.downloadUrl, setProgress);
        setPhase('decrypting');
        setProgress(0);
        const out = await decryptFile(container, key, setProgress);
        const name = safeFileName(out.meta.name);
        saveBlob(out.file, name);
        setSavedName(name);
        setPhase('done');
      }
      void meta.refetch();
    } catch (e) {
      setPhase('idle');
      if (e instanceof ApiError && e.fieldErrors.password) setFieldError({ password: e.fieldErrors.password });
      else setError(errorMessage(e));
      if (e instanceof ApiError && e.status === 410) void meta.refetch();
    }
  }

  const busy = phase === 'unlocking' || phase === 'downloading' || phase === 'decrypting';
  const gone = meta.error instanceof ApiError && (meta.error.status === 410 || meta.error.status === 404);

  return (
    <div className="mx-auto w-full max-w-lg px-4 py-10 sm:py-16">
      {meta.isPending && (
        <div className="space-y-3">
          <Skeleton className="h-8 w-2/3" />
          <Skeleton className="h-32" />
        </div>
      )}
      {meta.isError &&
        (gone ? (
          <EmptyState
            icon={Link2Off}
            title={(meta.error as ApiError).status === 404 ? 'This link doesn’t exist' : 'This link no longer works'}
            description={(meta.error as ApiError).status === 404 ? 'Check that you copied the whole link.' : errorMessage(meta.error)}
            mascot="sit"
          />
        ) : (
          <ErrorState error={meta.error} onRetry={() => meta.refetch()} />
        ))}
      {m && (
        <div className="rounded-2xl border border-border bg-surface p-5 sm:p-6">
          <div className="text-xs font-semibold uppercase tracking-wider text-muted">Shared file</div>
          <div className="mt-3 flex items-start gap-3">
            <span className={`inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-lg ${m.e2ee ? 'bg-accent/10 text-accent' : 'bg-surface-2 text-muted'}`}>
              {m.e2ee ? <LockKeyhole size={20} aria-hidden /> : <FileText size={20} aria-hidden />}
            </span>
            <div className="min-w-0">
              <h1 className="break-words text-lg font-semibold">{m.e2ee ? savedName ?? 'End-to-end encrypted file' : m.fileName}</h1>
              <p className="mt-0.5 text-sm text-muted">
                {formatBytes(m.sizeBytes)}
                {m.e2ee ? ' (encrypted size)' : m.contentType ? ` · ${typeLabel(m.contentType, m.fileName ?? '')}` : ''}
              </p>
            </div>
          </div>

          <ul className="mt-4 space-y-1.5 text-sm text-muted">
            <li className="flex items-center gap-2">
              <Clock size={14} aria-hidden /> {m.expiresAt ? `Available until ${new Date(m.expiresAt).toLocaleString()}` : 'No expiry date'}
            </li>
            {m.downloadsRemaining != null && (
              <li className="flex items-center gap-2">
                <Download size={14} aria-hidden /> {m.downloadsRemaining} {m.downloadsRemaining === 1 ? 'download' : 'downloads'} left
              </li>
            )}
            {!m.e2ee && m.scanStatus === 'CLEAN' && (
              <li className="flex items-center gap-2">
                <ShieldCheck size={14} aria-hidden /> {scanSummary(m.scanEngine).text}
              </li>
            )}
          </ul>

          {m.e2ee && <E2eeNote className="mt-4" />}
          {m.e2ee && <MemoryWarning sizeBytes={m.sizeBytes} className="mt-3" />}

          {phase === 'ready' && opened ? (
            <div className="mt-5">
              <StreamingSavePrompt
                opened={opened}
                onSaved={(name) => {
                  setSavedName(name);
                  setOpened(null);
                  setPhase('done');
                }}
              />
            </div>
          ) : missingKey ? (
            <div className="mt-5">
              <FormAlert message="This link is missing its key (the part after #k=). Ask the sender for the complete link; the server never has the key." />
            </div>
          ) : (
            <form
              className="mt-5 space-y-3"
              onSubmit={(e) => {
                e.preventDefault();
                void start();
              }}
            >
              {m.requiresPassword && (
                <Field label="Password" error={fieldError.password}>
                  {(p) => <Input {...p} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="off" />}
                </Field>
              )}
              {m.e2ee && m.keyMode === 'PASSPHRASE' && (
                <Field label="Passphrase" error={fieldError.passphrase} hint="Used in this browser to unwrap the key. It is never sent to the server.">
                  {(p) => <Input {...p} type="password" value={passphrase} onChange={(e) => setPassphrase(e.target.value)} autoComplete="off" />}
                </Field>
              )}
              <FormAlert message={error} />
              <Button type="submit" className="w-full" loading={busy}>
                {m.e2ee ? <KeyRound size={15} /> : <Download size={15} />}
                {phase === 'unlocking'
                  ? 'Checking…'
                  : phase === 'downloading'
                    ? `Downloading ${Math.round(progress * 100)}%`
                    : phase === 'decrypting'
                      ? `Decrypting ${Math.round(progress * 100)}%`
                      : m.e2ee
                        ? 'Download and decrypt'
                        : 'Download'}
              </Button>
              {phase === 'done' && (
                <p role="status" className="text-center text-sm text-accent">
                  {m.e2ee ? `Decrypted in your browser and saved as “${savedName}”.` : 'Your download has started.'}
                </p>
              )}
            </form>
          )}
        </div>
      )}
    </div>
  );
}

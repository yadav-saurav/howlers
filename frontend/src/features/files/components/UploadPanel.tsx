import { CheckCircle2, FileUp, LockKeyhole, UploadCloud, X } from 'lucide-react';
import { useRef, useState, type DragEvent } from 'react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Field';
import { Switch } from '@/components/ui/Switch';
import { useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '@/services/apiError';
import type { FileItem } from '@/types/files';
import { cn } from '@/utils/format';
import { fileKeys, uploadFile } from '../api';
import { encryptFile, exportKey, generateFileKey } from '../crypto';
import { formatBytes } from '../helpers';
import { keyStore } from '../keyStore';
import { E2eeNote } from './E2eeNote';

interface Job {
  id: number;
  name: string;
  size: number;
  e2ee: boolean;
  phase: 'encrypting' | 'uploading' | 'done' | 'error';
  progress: number;
  error?: string;
  abort: AbortController;
}

let seq = 0;

/**
 * Drag-and-drop (or pick) uploads with per-file progress. With "Encrypt in browser" on, each file is encrypted
 * locally first and the plaintext never leaves the device; the new key is handed to onEncrypted so the owner can
 * create a link straight away (the key exists nowhere else unless they choose to remember it on this device).
 */
export function UploadPanel({ maxFileBytes, onEncrypted }: { maxFileBytes?: number; onEncrypted: (file: FileItem, key: string) => void }) {
  const qc = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [e2ee, setE2ee] = useState(false);
  const [label, setLabel] = useState('');
  const [remember, setRemember] = useState(true);
  const [dragging, setDragging] = useState(false);
  const [jobs, setJobs] = useState<Job[]>([]);

  const patch = (id: number, p: Partial<Job>) => setJobs((js) => js.map((j) => (j.id === id ? { ...j, ...p } : j)));

  async function run(file: File) {
    const job: Job = { id: ++seq, name: file.name, size: file.size, e2ee, phase: e2ee ? 'encrypting' : 'uploading', progress: 0, abort: new AbortController() };
    setJobs((js) => [job, ...js].slice(0, 12));
    if (maxFileBytes && file.size > maxFileBytes) {
      patch(job.id, { phase: 'error', error: `Files must be ${formatBytes(maxFileBytes)} or smaller` });
      return;
    }
    try {
      if (e2ee) {
        const key = await generateFileKey();
        const container = await encryptFile(file, { name: file.name, type: file.type || 'application/octet-stream' }, key, {
          onProgress: (p) => patch(job.id, { progress: p }),
        });
        patch(job.id, { phase: 'uploading', progress: 0 });
        const saved = await uploadFile(container, 'encrypted.howlers', {
          e2ee: true,
          label: label.trim() || undefined,
          signal: job.abort.signal,
          onProgress: (p) => patch(job.id, { progress: p }),
        });
        const b64 = await exportKey(key);
        if (remember) keyStore.set(saved.id, b64);
        patch(job.id, { phase: 'done', progress: 1 });
        onEncrypted(saved, b64);
      } else {
        await uploadFile(file, file.name, { signal: job.abort.signal, onProgress: (p) => patch(job.id, { progress: p }) });
        patch(job.id, { phase: 'done', progress: 1 });
      }
      void qc.invalidateQueries({ queryKey: fileKeys.all });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        setJobs((js) => js.filter((j) => j.id !== job.id));
        return;
      }
      patch(job.id, { phase: 'error', error: errorMessage(e) });
    }
  }

  function addFiles(list: FileList | null) {
    if (!list) return;
    // Sequential: the server processes one upload per user at a time (quota check), and memory stays bounded.
    void Array.from(list).reduce((p, file) => p.then(() => run(file)), Promise.resolve());
  }

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    addFiles(e.dataTransfer.files);
  };

  return (
    <section aria-label="Upload files" className="space-y-3">
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={cn(
          'flex flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed px-4 py-8 text-center transition-colors',
          dragging ? 'border-brand bg-brand/5' : 'border-border',
        )}
      >
        {e2ee ? <LockKeyhole size={24} className="text-accent" aria-hidden /> : <UploadCloud size={24} className="text-muted" aria-hidden />}
        <p className="text-sm font-medium">
          Drop files here{e2ee ? ' to encrypt and upload' : ''} or{' '}
          <button type="button" className="font-semibold text-brand hover:underline" onClick={() => input.current?.click()}>
            choose files
          </button>
        </p>
        <p className="text-xs text-faint">
          Documents, images, audio, video, text and archives{maxFileBytes ? `, up to ${formatBytes(maxFileBytes)} each` : ''}. Executables
          and scripts are blocked.
        </p>
        <input
          ref={input}
          type="file"
          multiple
          className="sr-only"
          tabIndex={-1}
          aria-hidden
          onChange={(e) => {
            addFiles(e.target.files);
            e.target.value = '';
          }}
        />
      </div>

      <div className="rounded-2xl bg-surface-2/60 px-4">
        <Switch
          checked={e2ee}
          onCheckedChange={setE2ee}
          label="Encrypt in browser (end-to-end)"
          description="The server gets ciphertext only. No previews, no server-side checks, no recovery without the key."
        />
        {e2ee && (
          <div className="space-y-3 pb-4">
            <E2eeNote />
            <label className="block text-sm font-medium">
              Label in your library <span className="text-xs font-normal text-faint">optional, visible to the server</span>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} maxLength={120} placeholder="Encrypted file" className="mt-1" />
            </label>
            <label className="flex items-start gap-2 text-sm">
              <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="mt-1 accent-[rgb(var(--accent))]" />
              <span>
                Remember the key in this browser
                <span className="block text-xs text-muted">
                  Lets you download and share it again from this device. It stays in this browser's storage and is never uploaded.
                </span>
              </span>
            </label>
          </div>
        )}
      </div>

      {jobs.length > 0 && (
        <ul className="space-y-2" aria-live="polite">
          {jobs.map((j) => (
            <li key={j.id} className="rounded-lg border border-border px-3 py-2">
              <div className="flex items-center gap-2 text-sm">
                {j.phase === 'done' ? <CheckCircle2 size={16} className="text-accent" aria-hidden /> : <FileUp size={16} className="text-muted" aria-hidden />}
                <span className="min-w-0 flex-1 truncate">{j.name}</span>
                <span className="shrink-0 text-xs text-faint">{formatBytes(j.size)}</span>
                {(j.phase === 'uploading' || j.phase === 'encrypting') && (
                  <Button variant="ghost" size="sm" className="h-7 px-2" aria-label={`Cancel ${j.name}`} onClick={() => j.abort.abort()}>
                    <X size={14} />
                  </Button>
                )}
                {(j.phase === 'done' || j.phase === 'error') && (
                  <Button variant="ghost" size="sm" className="h-7 px-2" aria-label={`Dismiss ${j.name}`} onClick={() => setJobs((js) => js.filter((x) => x.id !== j.id))}>
                    <X size={14} />
                  </Button>
                )}
              </div>
              {j.phase === 'error' ? (
                <p role="alert" className="mt-1 text-xs text-danger">{j.error}</p>
              ) : (
                <div className="mt-2 flex items-center gap-2">
                  <div
                    className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2"
                    role="progressbar"
                    aria-label={`${j.phase === 'encrypting' ? 'Encrypting' : 'Uploading'} ${j.name}`}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={Math.round(j.progress * 100)}
                  >
                    <div className={cn('h-full rounded-full transition-[width]', j.phase === 'done' ? 'bg-accent' : 'bg-brand')} style={{ width: `${Math.round(j.progress * 100)}%` }} />
                  </div>
                  <span className="w-24 shrink-0 text-right text-xs text-faint">
                    {j.phase === 'encrypting' ? 'Encrypting' : j.phase === 'uploading' ? 'Uploading' : j.e2ee ? 'Encrypted' : 'Uploaded'}{' '}
                    {j.phase !== 'done' && `${Math.round(j.progress * 100)}%`}
                  </span>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

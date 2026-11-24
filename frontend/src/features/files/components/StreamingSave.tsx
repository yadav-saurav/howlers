import { HardDriveDownload, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { FormAlert } from '@/components/ui/Field';
import { errorMessage } from '@/services/apiError';
import { safeFileName } from '../crypto';
import { formatBytes } from '../helpers';
import { MEMORY_WARNING_BYTES, canStreamToDisk, isPickerCancel, saveChunksToDisk, type OpenedContainer } from '../streamDecrypt';

/**
 * Last step of a streamed E2EE download: the key is verified and the metadata decrypted, and the browser needs a click
 * to open its save dialog. Each chunk is then decrypted and written to the chosen file as it arrives.
 */
export function StreamingSavePrompt({ opened, onSaved }: { opened: OpenedContainer; onSaved: (name: string) => void }) {
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [spent, setSpent] = useState(false);
  const name = safeFileName(opened.meta.name);

  async function save() {
    setError(null);
    setBusy(true);
    try {
      await saveChunksToDisk(opened.chunks, name, opened.meta.size, setProgress);
      onSaved(name);
    } catch (e) {
      if (isPickerCancel(e)) return; // dialog dismissed before anything was read: the user can try again
      setSpent(true);
      setError(`${errorMessage(e)} The partial file was discarded.`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-muted">
        Key verified. “{name}” ({formatBytes(opened.meta.size)}) will be decrypted straight to a file on your device, one chunk at a time.
      </p>
      <FormAlert message={error} />
      {!spent && (
        <Button className="w-full" onClick={() => void save()} loading={busy}>
          <HardDriveDownload size={15} aria-hidden />
          {busy ? `Decrypting ${Math.round(progress * 100)}%` : 'Choose where to save'}
        </Button>
      )}
    </div>
  );
}

/** Shown before an in-memory decryption of a very large file in a browser that can't stream to disk. */
export function MemoryWarning({ sizeBytes, className }: { sizeBytes: number; className?: string }) {
  if (canStreamToDisk() || sizeBytes <= MEMORY_WARNING_BYTES) return null;
  return (
    <p className={`flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 p-3 text-sm ${className ?? ''}`} role="note">
      <TriangleAlert size={15} className="mt-0.5 shrink-0 text-warning" aria-hidden />
      <span>
        This file is {formatBytes(sizeBytes)}. This browser decrypts it in memory, which can fail or slow your device down for files this
        large. A desktop browser that supports saving directly to disk (such as Chrome or Edge) decrypts it chunk by chunk instead.
      </span>
    </p>
  );
}

import { LockKeyhole } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cn } from '@/utils/format';

/** The honest one-paragraph summary of what browser encryption does and doesn't do. */
export function E2eeNote({ className, compact }: { className?: string; compact?: boolean }) {
  return (
    <div className={cn('rounded-lg border border-border bg-surface-2/60 p-3 text-xs leading-relaxed text-muted', className)}>
      <p className="flex items-start gap-2">
        <LockKeyhole size={14} className="mt-0.5 shrink-0 text-accent" aria-hidden />
        <span>
          Your browser encrypts the file (AES-256-GCM) before it leaves your device, including its name and type. The server
          stores only ciphertext and never receives the key, so it <strong className="text-text">can't preview, scan or recover</strong> this
          file. If you lose every link and key, nobody can decrypt it.
          {!compact && (
            <> Anyone who has the full link (or the link and the passphrase) can decrypt it. Sizes and timing are still visible to the server.</>
          )}{' '}
          <Link to="/security/e2ee" className="font-medium text-brand hover:underline">
            Threat model
          </Link>
        </span>
      </p>
    </div>
  );
}

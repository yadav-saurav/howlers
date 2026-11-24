import { RefreshCw, X } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { applyUpdate, useServiceWorker } from './serviceWorker';

/** "A new version is ready" banner, shown when an updated service worker is waiting (D-039). */
export function UpdatePrompt() {
  const waiting = useServiceWorker((s) => s.waiting);
  const dismissed = useServiceWorker((s) => s.dismissed);
  const dismiss = useServiceWorker((s) => s.dismiss);
  if (!waiting || dismissed) return null;
  return (
    <div
      role="status"
      className="fixed inset-x-3 bottom-20 z-50 mx-auto flex max-w-md items-center gap-3 rounded-lg border border-border bg-surface px-4 py-3 shadow-lg sm:bottom-6"
    >
      <RefreshCw size={18} className="shrink-0 text-accent" aria-hidden="true" />
      <p className="min-w-0 flex-1 text-sm">A new version of Howlers is ready.</p>
      <Button size="sm" onClick={applyUpdate}>Reload</Button>
      <button type="button" onClick={dismiss} className="rounded p-1 text-muted hover:text-text" aria-label="Later">
        <X size={16} />
      </button>
    </div>
  );
}

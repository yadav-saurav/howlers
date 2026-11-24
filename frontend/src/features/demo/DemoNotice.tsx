import { FlaskConical, X } from 'lucide-react';
import { useState } from 'react';
import { isDismissed, rememberDismissed, useDemoStatus } from './api';

const storage = () => (typeof window === 'undefined' ? undefined : window.localStorage);

/**
 * A small, dismissible strip saying that seeded content is fictional, shown only when the API reports demo data.
 * Seeded activity must never pass as real user activity.
 */
export function DemoNotice() {
  const q = useDemoStatus();
  const [dismissed, setDismissed] = useState(() => isDismissed(storage()));
  if (!q.data?.demoData || dismissed) return null;
  return (
    <div role="note" className="border-b border-warning/30 bg-warning/10 px-4 py-2 text-sm">
      <div className="mx-auto flex max-w-5xl items-start gap-2">
        <FlaskConical size={16} className="mt-0.5 shrink-0 text-warning" aria-hidden="true" />
        <p className="min-w-0 flex-1 text-text">
          <span className="font-semibold">Demo data.</span>{' '}
          <span className="text-muted">
            {q.data.message ?? 'The demo accounts and their posts, votes and moderation history are fictional sample content, not real activity.'}
          </span>
        </p>
        <button
          type="button"
          aria-label="Dismiss the demo data notice"
          className="shrink-0 rounded-md p-1 text-muted hover:bg-surface-2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand"
          onClick={() => {
            rememberDismissed(storage());
            setDismissed(true);
          }}
        >
          <X size={16} aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

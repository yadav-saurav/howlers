import { AlertTriangle, type LucideIcon } from 'lucide-react';
import { useEffect, useRef, type ReactNode } from 'react';
import { Mascot, type MascotPose } from '@/design-system/Mascot';
import { connectionTrouble, errorMessage } from '@/services/apiError';
import { Button } from './Button';
import { Spinner } from './Spinner';

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  mascot,
  headingLevel = 3,
}: {
  icon: LucideIcon;
  title: string;
  description?: string;
  action?: ReactNode;
  /** Show a wolf pup in this pose instead of the icon (decorative; see design-system/Mascot.tsx). */
  mascot?: MascotPose;
  /** 1 when the empty state is the whole page (e.g. 404), so the page still has a top-level heading. */
  headingLevel?: 1 | 2 | 3;
}) {
  const Heading = `h${headingLevel}` as const;
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      {mascot ? (
        <Mascot pose={mascot} size={88} className="mb-3" />
      ) : (
        <span className="mb-4 inline-flex h-11 w-11 items-center justify-center rounded-lg border border-border bg-surface-2 text-muted">
          <Icon size={20} />
        </span>
      )}
      <Heading className="font-serif text-lg font-semibold">{title}</Heading>
      {description && <p className="mt-1.5 max-w-sm text-sm text-muted">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

/** Offline and maintenance errors get the sleepy pup and a gentler heading; the server's message is still shown. */
const TROUBLE_TITLES = {
  offline: 'The pack can’t hear you right now',
  maintenance: 'Howlers is taking a short nap',
} as const;

export function ErrorState({ error, onRetry, title }: { error: unknown; onRetry?: () => void; title?: string }) {
  const trouble = connectionTrouble(error);
  return (
    <div role="alert" className="flex flex-col items-center px-6 py-12 text-center">
      {trouble ? (
        <Mascot pose="sleepy" size={80} className="mb-3" />
      ) : (
        <span className="mb-4 inline-flex h-11 w-11 items-center justify-center rounded-lg border border-danger/30 bg-danger/10 text-danger">
          <AlertTriangle size={20} />
        </span>
      )}
      <h3 className="font-serif text-lg font-semibold">{title ?? (trouble ? TROUBLE_TITLES[trouble] : 'Could not load this')}</h3>
      <p className="mt-1.5 max-w-sm text-sm text-muted">{errorMessage(error)}</p>
      {onRetry && (
        <Button variant="secondary" size="sm" className="mt-5" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

/** Invisible sentinel that calls onVisible when scrolled into view; falls back to a button. */
export function LoadMore({
  hasMore,
  loading,
  onLoad,
}: {
  hasMore: boolean;
  loading: boolean;
  onLoad: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el || !hasMore || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && !loading) onLoad();
      },
      { rootMargin: '600px' },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [hasMore, loading, onLoad]);

  if (!hasMore) return null;
  return (
    <div ref={ref} className="flex justify-center py-6">
      {loading ? (
        <Spinner className="text-muted" />
      ) : (
        <Button variant="ghost" size="sm" onClick={onLoad}>
          Load more
        </Button>
      )}
    </div>
  );
}

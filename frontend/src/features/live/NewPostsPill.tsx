import { ArrowUp } from 'lucide-react';
import { newPostsLabel } from './logic';

/**
 * Sticky "↑ N new posts" pill pinned just under the top bar. It takes no space in the flow (zero-height sticky row),
 * so the list never shifts when it appears. A polite live region announces the count for screen readers.
 */
export function NewPostsPill({ count, onShow }: { count: number; onShow: () => void }) {
  return (
    <>
      <div className="pointer-events-none sticky top-[68px] z-20 flex h-0 justify-center">
        {count > 0 && (
          <button
            type="button"
            onClick={onShow}
            className="pointer-events-auto mt-1 inline-flex h-9 items-center gap-1.5 rounded-full bg-brand px-4 text-sm font-semibold text-brand-fg shadow-float transition-colors hover:bg-brand/90"
          >
            <ArrowUp size={15} aria-hidden="true" />
            {newPostsLabel(count)}
            <span className="sr-only">, show them</span>
          </button>
        )}
      </div>
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {count > 0 ? `${newPostsLabel(count)} available at the top of the feed` : ''}
      </div>
    </>
  );
}

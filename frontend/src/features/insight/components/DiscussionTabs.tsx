import { MessageSquare, Network, ScrollText } from 'lucide-react';
import { useCallback, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { toast } from '@/components/ui/Toast';
import { cn } from '@/utils/format';
import { ContextModeView } from './ContextModeView';
import { DigestPanel } from './DigestPanel';

type View = 'comments' | 'digest' | 'context';

/**
 * The three ways to read a discussion. The thread stays the source of truth; Digest and Context Mode are
 * alternative lenses whose every item links back into it.
 */
export function DiscussionTabs({ postId, commentCount, comments }: { postId: number; commentCount: number; comments: ReactNode }) {
  const [params, setParams] = useSearchParams();
  const raw = params.get('view');
  const view: View = raw === 'digest' || raw === 'context' ? raw : 'comments';

  const setView = useCallback(
    (v: View) => {
      const n = new URLSearchParams(params);
      if (v === 'comments') n.delete('view');
      else n.set('view', v);
      setParams(n, { replace: true });
    },
    [params, setParams],
  );

  const jump = useCallback(
    (commentId: number) => {
      setView('comments');
      let tries = 0;
      const find = () => {
        const el = document.getElementById(`c-${commentId}`);
        if (el) {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          el.classList.add('ring-2', 'ring-brand/50', 'rounded-xl');
          window.setTimeout(() => el.classList.remove('ring-2', 'ring-brand/50', 'rounded-xl'), 1800);
        } else if (tries++ < 10) {
          window.setTimeout(find, 150);
        } else {
          toast.info('That comment is further down the thread. Try “Load more” or sort by New.');
        }
      };
      window.setTimeout(find, 50);
    },
    [setView],
  );

  const tabs: { v: View; label: string; icon: ReactNode }[] = [
    { v: 'comments', label: `Comments (${commentCount})`, icon: <MessageSquare size={15} /> },
    { v: 'digest', label: 'Digest', icon: <ScrollText size={15} /> },
    { v: 'context', label: 'Context Mode', icon: <Network size={15} /> },
  ];

  return (
    <div>
      <div role="tablist" aria-label="Discussion views" className="mb-4 flex gap-1 overflow-x-auto [scrollbar-width:none]">
        {tabs.map((t) => (
          <button
            key={t.v}
            role="tab"
            type="button"
            aria-selected={view === t.v}
            onClick={() => setView(t.v)}
            className={cn(
              'inline-flex h-9 items-center gap-1.5 whitespace-nowrap rounded-full px-4 text-sm font-semibold transition-colors',
              view === t.v ? 'bg-surface-3 text-text' : 'text-muted hover:bg-surface-2 hover:text-text',
            )}
          >
            {t.icon}
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel">
        {view === 'comments' && comments}
        {view === 'digest' && <DigestPanel postId={postId} commentCount={commentCount} onJump={jump} />}
        {view === 'context' && <ContextModeView postId={postId} commentCount={commentCount} onJump={jump} />}
      </div>
    </div>
  );
}

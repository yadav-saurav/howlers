import { MessagesSquare } from 'lucide-react';
import { useParams, useSearchParams } from 'react-router-dom';
import { EmptyState } from '@/components/ui/States';
import { ConversationList } from '@/features/messages/components/ConversationList';
import { Thread } from '@/features/messages/components/Thread';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { usePlayer } from '@/store/player';
import { cn } from '@/utils/format';

/** Two panes on desktop (list + thread); one pane at a time on mobile. */
export default function MessagesPage() {
  const { id: raw } = useParams();
  const id = raw ? Number(raw) : null;
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const hasPlayer = usePlayer((s) => s.current != null);
  useDocumentTitle('Messages'); // Thread refines this once the conversation loads

  const setQ = (v: string) =>
    setParams((p) => {
      if (v) p.set('q', v);
      else p.delete('q');
      return p;
    }, { replace: true });

  return (
    <div
      className={cn(
        'mx-auto flex w-full max-w-5xl overflow-hidden border-border bg-surface md:mt-4 md:rounded-lg md:border',
        // Fill the viewport below the top bar, leaving room for the mobile tab bar and the mini player.
        hasPlayer ? 'h-[calc(100dvh-56px-160px)] md:h-[calc(100dvh-56px-112px)]' : 'h-[calc(100dvh-56px-80px)] md:h-[calc(100dvh-56px-56px)]',
      )}
    >
      <div className={cn('w-full shrink-0 border-border md:w-80 md:border-r', id != null && 'hidden md:block')}>
        <ConversationList activeId={id} q={q} onQueryChange={setQ} />
      </div>
      <div className={cn('min-w-0 flex-1', id == null && 'hidden md:block')}>
        {id != null && Number.isFinite(id) ? (
          <Thread key={id} id={id} />
        ) : (
          <div className="flex h-full items-center justify-center">
            <EmptyState icon={MessagesSquare} title="Your messages" description="Pick a conversation, or start a new one from the list." mascot="headphones" />
          </div>
        )}
      </div>
    </div>
  );
}

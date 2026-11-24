import { ArrowLeft, Lock, MessagesSquare } from 'lucide-react';
import { useCallback, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { SortMenu } from '@/features/posts/components/FeedControls';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { errorMessage } from '@/services/apiError';
import type { CommentSort } from '@/types/api';
import { useComments, useCreateComment, useLoadReplies } from '../hooks';
import { findNode } from '../tree';
import { CommentComposer } from './CommentComposer';
import { CommentItem } from './CommentItem';
import { ThreadContext, type ThreadCtx } from './threadContext';

const SORTS: { value: CommentSort; label: string }[] = [
  { value: 'best', label: 'Best' },
  { value: 'top', label: 'Top' },
  { value: 'new', label: 'New' },
  { value: 'controversial', label: 'Controversial' },
  { value: 'old', label: 'Oldest' },
];

export function CommentSection({
  postId,
  locked,
  commentCount,
  opId = null,
  canModerate = false,
  communitySlug = '',
}: {
  postId: number;
  locked: boolean;
  commentCount: number;
  opId?: number | null;
  canModerate?: boolean;
  communitySlug?: string;
}) {
  const { isAuthed } = useAuth();
  const [sort, setSort] = useState<CommentSort>('best');
  const [focusId, setFocusId] = useState<number | null>(null);
  const desktop = useMediaQuery('(min-width: 768px)');
  const comments = useComments(postId, sort);
  const create = useCreateComment(postId);
  const loadReplies = useLoadReplies(postId, sort);
  const cursors = useRef(new Map<number, string | null>());
  const [loadingFor, setLoadingFor] = useState<number | null>(null);
  const topRef = useRef<HTMLDivElement>(null);

  const loadMore = useCallback(
    (commentId: number) => {
      const cursor = cursors.current.get(commentId);
      if (cursor === null) return;
      setLoadingFor(commentId);
      loadReplies.mutate(
        { commentId, cursor: cursor ?? undefined },
        {
          onSuccess: (page) => cursors.current.set(commentId, page.nextCursor),
          onError: (e) => toast.error(errorMessage(e)),
          onSettled: () => setLoadingFor(null),
        },
      );
    },
    [loadReplies],
  );

  const ctx: ThreadCtx = useMemo(
    () => ({
      postId,
      locked,
      opId,
      canModerate,
      communitySlug,
      maxLevel: desktop ? 6 : 4,
      focus: (id) => {
        setFocusId(id);
        topRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      },
      loadMore,
      loadingFor,
    }),
    [postId, locked, opId, canModerate, communitySlug, desktop, loadMore, loadingFor],
  );

  const forest = comments.data?.pages.flatMap((p) => p.items) ?? [];
  const focused = focusId != null ? findNode(forest, focusId) : null;

  return (
    <section id="comments" aria-labelledby="comments-h" ref={topRef} className="scroll-mt-20">
      <h2 id="comments-h" className="sr-only">
        {commentCount} {commentCount === 1 ? 'comment' : 'comments'}
      </h2>
      {locked ? (
        <div className="mb-4 flex items-center gap-2 rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-sm text-warning">
          <Lock size={15} /> Comments are locked by moderators.
        </div>
      ) : isAuthed ? (
        <div className="mb-5">
          <CommentComposer
            loading={create.isPending}
            onSubmit={(body, reset) =>
              create.mutate({ body }, { onSuccess: () => { reset(); toast.success('Comment posted'); }, onError: (e) => toast.error(errorMessage(e)) })
            }
          />
        </div>
      ) : (
        <div className="mb-5 flex flex-wrap items-center justify-between gap-3 rounded-md border border-border bg-surface-2/50 px-4 py-3 text-sm">
          <span className="text-muted">Log in to join the discussion.</span>
          <Link to={`/login?next=${encodeURIComponent(window.location.pathname)}`} className={buttonClasses('primary', 'sm')}>Log in</Link>
        </div>
      )}

      <div className="mb-1 flex items-center gap-1 text-xs text-muted">
        <span>Sort by:</span>
        <SortMenu title="Sort comments" value={sort} options={SORTS} onChange={setSort} />
      </div>

      <ThreadContext.Provider value={ctx}>
        {comments.isPending && (
          <div className="space-y-5">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="space-y-2"><Skeleton className="h-3 w-40" /><Skeleton className="h-4 w-11/12" /><Skeleton className="h-4 w-2/3" /></div>
            ))}
          </div>
        )}
        {comments.isError && <ErrorState error={comments.error} onRetry={() => comments.refetch()} />}
        {comments.data && forest.length === 0 && (
          <EmptyState icon={MessagesSquare} title="No comments yet" description={locked ? 'This thread is locked.' : 'Start the conversation with a thoughtful reply.'} />
        )}

        {focused ? (
          <div>
            <button type="button" onClick={() => setFocusId(null)} className="mb-2 inline-flex items-center gap-1.5 text-sm font-medium text-accent hover:underline">
              <ArrowLeft size={15} /> Back to full discussion
            </button>
            <CommentItem node={focused} level={0} />
          </div>
        ) : (
          <div>
            {forest.map((n) => <CommentItem key={n.id} node={n} level={0} />)}
          </div>
        )}
        {!focused && (
          <LoadMore hasMore={!!comments.hasNextPage} loading={comments.isFetchingNextPage} onLoad={() => comments.fetchNextPage()} />
        )}
      </ThreadContext.Provider>
    </section>
  );
}

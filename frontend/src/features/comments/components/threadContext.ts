import { createContext, useContext } from 'react';

export interface ThreadCtx {
  postId: number;
  locked: boolean;
  /** Post author's id, used for the OP badge. */
  opId: number | null;
  /** Viewer moderates this community: comment Remove/Approve are shown. */
  canModerate: boolean;
  communitySlug: string;
  /** Visual nesting limit (4 on mobile, 6 on desktop); deeper replies continue in focus mode. */
  maxLevel: number;
  focus: (commentId: number) => void;
  loadMore: (commentId: number) => void;
  loadingFor: number | null;
}

export const ThreadContext = createContext<ThreadCtx | null>(null);

export function useThread(): ThreadCtx {
  const ctx = useContext(ThreadContext);
  if (!ctx) throw new Error('useThread must be used inside <CommentSection>');
  return ctx;
}

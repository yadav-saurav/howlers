import { useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { commentsApi } from '@/features/comments/api';
import { cachedForests, patchForests } from '@/features/comments/hooks';
import { forestHas, insertLiveComment, patchCommentScores } from '@/features/comments/tree';
import { patchPostEverywhere } from '@/features/posts/cache';
import { refreshReactions } from '@/features/reactions/api';
import { useTopic } from '@/features/realtime/RealtimeProvider';
import { ApiError } from '@/services/apiError';
import { mk } from '@/services/queryKeys';
import { useAuthStore } from '@/store/authStore';
import type { PostEvent } from '@/types/live';
import { applyPostStats } from './logic';
import { useNewCommentsStore } from './newComments';

/**
 * Keeps an open post live: subscribes to `/topic/post.{postId}` while `enabled` (pass true only once the post detail
 * loaded, since an unauthorized SUBSCRIBE closes the whole socket) and patches React Query caches in place.
 */
export function usePostLive(postId: number, enabled: boolean) {
  const qc = useQueryClient();
  const meId = useAuthStore((s) => s.user?.id ?? null);
  const inflight = useRef(new Set<number>());

  useTopic<PostEvent>(enabled ? `/topic/post.${postId}` : null, (ev) => {
    if (!ev || typeof ev !== 'object' || ev.postId !== postId) return;
    switch (ev.type) {
      case 'POST_STATS':
        // My vote is optimistic and its response sets the authoritative score; a batched frame may predate it.
        if (qc.isMutating({ mutationKey: mk.postVote(postId) }) > 0) return;
        patchPostEverywhere(qc, postId, (p) => applyPostStats(p, ev));
        break;
      case 'COMMENT_SCORES': {
        const scores: Record<string, number> = {};
        for (const [id, s] of Object.entries(ev.scores ?? {})) {
          if (qc.isMutating({ mutationKey: mk.commentVote(Number(id)) }) === 0) scores[id] = s;
        }
        if (Object.keys(scores).length) patchForests(qc, postId, (nodes) => patchCommentScores(nodes, scores));
        break;
      }
      case 'REACTIONS':
        refreshReactions(qc, ev.targetType, ev.targetId);
        break;
      case 'COMMENT_CREATED': {
        const { commentId, parentId } = ev;
        // My own comments are inserted by the create mutation.
        if (ev.authorId === meId || inflight.current.has(commentId)) return;
        const forests = cachedForests(qc, postId);
        if (forestHas(forests, commentId)) return;
        // A reply whose parent isn't loaded has nowhere to go; POST_STATS still updates the total.
        if (parentId != null && !forestHas(forests, parentId)) return;
        inflight.current.add(commentId);
        commentsApi
          .get(commentId)
          .then((c) => {
            if (forestHas(cachedForests(qc, postId), c.id)) return;
            patchForests(qc, postId, (nodes, i) => insertLiveComment(nodes, c, i === 0));
            if (!c.blocked) useNewCommentsStore.getState().mark(c.id);
          })
          .catch((e) => {
            // 404: deleted or not visible to me by the time I asked. Anything else: the next refetch catches up.
            if (!(e instanceof ApiError)) console.warn('live comment fetch failed', e);
          })
          .finally(() => inflight.current.delete(commentId));
        break;
      }
    }
  });
}

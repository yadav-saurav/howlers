import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import { useAuthStore } from '@/store/authStore';
import type { ReactionTargetType, TargetReactions, ToggleResult } from '@/types/reactions';
import { createReactionBatcher } from './batcher';
import { applyToggle } from './logic';

export const reactionApi = {
  list: (targetType: ReactionTargetType, ids: number[]) =>
    http.get<TargetReactions[]>('/reactions', { targetType, ids: ids.join(',') }),
  /** `on` omitted toggles. */
  set: (targetType: ReactionTargetType, targetId: number, reaction: string, on?: boolean) =>
    http.post<ToggleResult>('/reactions', { targetType, targetId, reaction, on }),
};

export const reactionKeys = {
  all: ['reactions'] as const,
  target: (type: ReactionTargetType, id: number) => ['reactions', type, id] as const,
};

const batcher = createReactionBatcher(reactionApi.list);

export function useReactions(type: ReactionTargetType, id: number, enabled = true) {
  return useQuery({
    queryKey: reactionKeys.target(type, id),
    queryFn: () => batcher.load(type, id),
    enabled: enabled && Number.isFinite(id),
    staleTime: 30_000,
  });
}

/** Optimistic toggle; the response replaces the summary with the server's numbers, an error rolls back. */
export function useToggleReaction(type: ReactionTargetType, id: number) {
  const qc = useQueryClient();
  const myName = useAuthStore((s) => s.user?.username ?? null);
  const key = reactionKeys.target(type, id);
  return useMutation({
    mutationKey: ['react', type, id],
    mutationFn: ({ reaction, on }: { reaction: string; on: boolean }) => reactionApi.set(type, id, reaction, on),
    onMutate: async ({ reaction, on }) => {
      await qc.cancelQueries({ queryKey: key });
      const prev = qc.getQueryData<TargetReactions>(key);
      const base = prev ?? { targetType: type, targetId: id, reactions: [] };
      qc.setQueryData(key, applyToggle(base, reaction, on, myName));
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(key, ctx.prev);
      else void qc.invalidateQueries({ queryKey: key });
    },
    onSuccess: (res) => qc.setQueryData(key, res.summary),
  });
}

/** Live nudge: refetch one target's summary unless my own toggle is still in flight. */
export function refreshReactions(qc: ReturnType<typeof useQueryClient>, type: ReactionTargetType, id: number) {
  if (qc.isMutating({ mutationKey: ['react', type, id] }) > 0) return;
  void qc.invalidateQueries({ queryKey: reactionKeys.target(type, id) });
}

import { useQueryClient, type QueryKey } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { refetchFromFirstPage } from '@/features/posts/cache';
import { useTopic } from '@/features/realtime/RealtimeProvider';
import { useAuthStore } from '@/store/authStore';
import { collectNewPost, type FeedScope } from './logic';

/**
 * Counts posts announced on `destination` that belong in the feed cached under `queryKey`, without touching the list.
 * The count resets whenever the feed (query key) changes. `showNew()` scrolls to the top, refetches the feed from its
 * first page and clears the count. Pass `destination: null` to stay unsubscribed (anonymous, or not allowed to view).
 */
export function useNewPosts({ destination, scope, queryKey }: { destination: string | null; scope: FeedScope; queryKey: QueryKey }) {
  const qc = useQueryClient();
  const meId = useAuthStore((s) => s.user?.id ?? null);
  const [seen, setSeen] = useState<ReadonlySet<number>>(() => new Set());
  const scopeRef = useRef(scope);
  scopeRef.current = scope;
  const feedId = JSON.stringify(queryKey);
  const keyRef = useRef(queryKey);
  keyRef.current = queryKey;

  useEffect(() => setSeen(new Set()), [feedId, destination]);

  useTopic<unknown>(destination, (raw) => setSeen((s) => collectNewPost(s, raw, scopeRef.current, meId)));

  const showNew = useCallback(() => {
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
    setSeen(new Set());
    void refetchFromFirstPage(qc, keyRef.current);
  }, [qc]);

  return { count: seen.size, showNew };
}

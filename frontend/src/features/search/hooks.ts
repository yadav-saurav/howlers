import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { qk } from '@/services/queryKeys';
import type { SearchType } from '@/types/api';
import { searchApi, type SearchParams } from './api';

export function useSearch<T extends SearchType>(q: string, type: T, params: SearchParams, active = true) {
  return useInfiniteQuery({
    queryKey: qk.search(q, type, params as Record<string, unknown>),
    queryFn: ({ pageParam }) => searchApi.search(q, type, params, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: active && q.trim().length > 0,
  });
}

export function useSuggest(raw: string) {
  const q = useDebouncedValue(raw.trim(), 200);
  return useQuery({
    queryKey: qk.suggest(q),
    queryFn: ({ signal }) => searchApi.suggest(q, signal),
    enabled: q.length >= 2,
    staleTime: 30_000,
  });
}

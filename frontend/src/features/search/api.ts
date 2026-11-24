import { http } from '@/services/apiClient';
import type { CommunitySummary, CursorPage, PostSummary, SearchSuggest, SearchType, TimeRange, UserSummary } from '@/types/api';

export interface SearchParams {
  community?: string;
  sort?: 'relevance' | 'new' | 'top';
  t?: TimeRange;
}

export type SearchItem<T extends SearchType> = T extends 'posts'
  ? PostSummary
  : T extends 'communities'
    ? CommunitySummary
    : UserSummary;

export const searchApi = {
  search: <T extends SearchType>(q: string, type: T, params: SearchParams, cursor?: string) =>
    http.get<CursorPage<SearchItem<T>>>('/search', { q, type, ...params, cursor }),
  suggest: (q: string, signal?: AbortSignal) => http.get<SearchSuggest>('/search/suggest', { q }, signal),
};

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import type { Collection, CollectionItem, SharedCollection } from '@/types/knowledge';

export interface ItemFilter {
  q?: string;
  tag?: string;
  type?: 'POST' | 'COMMENT' | '';
}

export const collectionsApi = {
  mine: () => http.get<Collection[]>('/me/collections'),
  create: (body: { name: string; description?: string }) => http.post<Collection>('/me/collections', body),
  update: (id: number, body: { name?: string; description?: string; shared?: boolean }) => http.patch<Collection>(`/me/collections/${id}`, body),
  remove: (id: number) => http.del<void>(`/me/collections/${id}`),
  items: (id: number | null, f: ItemFilter) =>
    id == null
      ? http.get<CollectionItem[]>('/me/library/items', { q: f.q, tag: f.tag, type: f.type })
      : http.get<CollectionItem[]>(`/me/collections/${id}/items`, { q: f.q, tag: f.tag, type: f.type }),
  tags: () => http.get<{ tag: string; count: number }[]>('/me/library/tags'),
  membership: (postId: number, commentId?: number) =>
    http.get<{ collectionId: number; itemId: number }[]>('/me/library/membership', { postId, commentId }),
  add: (collectionId: number, body: { type: 'POST' | 'COMMENT'; postId: number; commentId?: number; note?: string; tags?: string[] }) =>
    http.post<CollectionItem>(`/me/collections/${collectionId}/items`, body),
  updateItem: (itemId: number, body: { note?: string; tags?: string[]; collectionId?: number }) =>
    http.patch<CollectionItem>(`/me/collection-items/${itemId}`, body),
  removeItem: (itemId: number) => http.del<void>(`/me/collection-items/${itemId}`),
  reorder: (collectionId: number, itemIds: number[]) => http.put<void>(`/me/collections/${collectionId}/order`, { itemIds }),
  shared: (token: string) => http.get<SharedCollection>(`/collections/shared/${encodeURIComponent(token)}`),
};

export const collectionKeys = {
  all: ['collections'] as const,
  mine: ['collections', 'mine'] as const,
  items: (id: number | null, f: ItemFilter) => ['collections', 'items', id, f] as const,
  tags: ['collections', 'tags'] as const,
  membership: (postId: number, commentId?: number) => ['collections', 'membership', postId, commentId ?? 0] as const,
  shared: (token: string) => ['collections', 'shared', token] as const,
};

export function useMyCollections(enabled = true) {
  return useQuery({ queryKey: collectionKeys.mine, queryFn: collectionsApi.mine, enabled });
}

export function useCollectionItems(id: number | null, f: ItemFilter) {
  return useQuery({ queryKey: collectionKeys.items(id, f), queryFn: () => collectionsApi.items(id, f), placeholderData: (p) => p });
}

export function useLibraryTags() {
  return useQuery({ queryKey: collectionKeys.tags, queryFn: collectionsApi.tags });
}

export function useMembership(postId: number, commentId: number | undefined, enabled: boolean) {
  return useQuery({ queryKey: collectionKeys.membership(postId, commentId), queryFn: () => collectionsApi.membership(postId, commentId), enabled });
}

export function useSharedCollection(token: string) {
  return useQuery({ queryKey: collectionKeys.shared(token), queryFn: () => collectionsApi.shared(token), retry: false });
}

/** Every collection mutation invalidates the whole collections namespace: lists are small and per-user. */
export function useCollectionMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: collectionKeys.all }) });
}

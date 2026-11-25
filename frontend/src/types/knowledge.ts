import type { PostSummary, UserSummary } from './api';

/** Mirrors backend app.howlers.insight / app.howlers.collection DTOs (docs/API.md → Knowledge layer). */
export interface Collection {
  id: number;
  name: string;
  description: string;
  shared: boolean;
  shareToken: string | null;
  itemCount: number;
  previewImages: string[];
  updatedAt: string;
}

export interface CollectionItem {
  id: number;
  collectionId: number;
  type: 'POST' | 'COMMENT';
  post: PostSummary;
  comment: { id: number; excerpt: string; author: UserSummary | null; score: number; deleted: boolean } | null;
  note: string;
  tags: string[];
  position: number;
  createdAt: string;
}

export interface SharedCollection {
  collection: Collection;
  owner: UserSummary | null;
  items: CollectionItem[];
}

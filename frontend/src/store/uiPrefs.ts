import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

export type ViewMode = 'card' | 'compact';

export interface RecentPost {
  id: number;
  title: string;
  slug: string;
  iconUrl: string | null;
  score: number;
  commentCount: number;
  thumb: string | null;
}

export interface RecentCommunity {
  slug: string;
  iconUrl: string | null;
}

interface UiPrefs {
  viewMode: ViewMode;
  recentPosts: RecentPost[];
  recentCommunities: RecentCommunity[];
  /** Left-nav section keys the user has collapsed. */
  collapsed: string[];
  setViewMode: (m: ViewMode) => void;
  visitPost: (p: RecentPost) => void;
  visitCommunity: (c: RecentCommunity) => void;
  clearRecentPosts: () => void;
  toggleSection: (key: string) => void;
}

/**
 * Per-device UI conveniences only (view mode, recently visited, collapsed nav sections).
 * Nothing here is authoritative; the server never reads it.
 */
export const useUiPrefs = create<UiPrefs>()(
  persist(
    (set) => ({
      viewMode: 'card',
      recentPosts: [],
      recentCommunities: [],
      collapsed: [],
      setViewMode: (viewMode) => set({ viewMode }),
      visitPost: (p) => set((s) => ({ recentPosts: [p, ...s.recentPosts.filter((x) => x.id !== p.id)].slice(0, 8) })),
      visitCommunity: (c) =>
        set((s) => ({ recentCommunities: [c, ...s.recentCommunities.filter((x) => x.slug !== c.slug)].slice(0, 5) })),
      clearRecentPosts: () => set({ recentPosts: [] }),
      toggleSection: (key) =>
        set((s) => ({ collapsed: s.collapsed.includes(key) ? s.collapsed.filter((k) => k !== key) : [...s.collapsed, key] })),
    }),
    {
      name: 'howlers-ui',
      storage: createJSONStorage(() => {
        try {
          return window.localStorage;
        } catch {
          return sessionStorage;
        }
      }),
      version: 1,
    },
  ),
);

import { create } from 'zustand';

/** How long a comment that arrived live keeps its highlight. */
export const NEW_COMMENT_HIGHLIGHT_MS = 4_000;

interface NewCommentsState {
  /** Comment ids that arrived over the socket and are still highlighted. Client-only; never part of the server shape. */
  ids: Record<number, true>;
  mark: (id: number) => void;
}

export const useNewCommentsStore = create<NewCommentsState>((set) => ({
  ids: {},
  mark: (id) => {
    set((s) => ({ ids: { ...s.ids, [id]: true } }));
    window.setTimeout(
      () =>
        set((s) => {
          if (!s.ids[id]) return s;
          const ids = { ...s.ids };
          delete ids[id];
          return { ids };
        }),
      NEW_COMMENT_HIGHLIGHT_MS,
    );
  },
}));

export function useIsNewComment(id: number): boolean {
  return useNewCommentsStore((s) => !!s.ids[id]);
}

import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

/** The minimum the player needs to play an episode and label it, independent of the API cache. */
export interface PlayerTrack {
  id: number;
  title: string;
  communitySlug: string;
  thumbnailUrl: string | null;
  mediaUrl: string;
  durationSeconds: number | null;
  chapters: { startSeconds: number; title: string }[];
  /** True when the audio is an AI-generated voice; the player always labels it. */
  aiVoice?: boolean;
}

export const RATES = [0.75, 1, 1.25, 1.5, 1.75, 2] as const;

interface PlayerState {
  current: PlayerTrack | null;
  queue: PlayerTrack[];
  /** Last known position per episode on this device (fallback when the server has none). */
  positions: Record<number, number>;
  rate: number;
  playing: boolean;
  /** One-shot seek request consumed by the audio engine. */
  seekTo: number | null;
  play: (track: PlayerTrack, startAt?: number) => void;
  setPlaying: (p: boolean) => void;
  requestSeek: (t: number) => void;
  clearSeek: () => void;
  rememberPosition: (id: number, t: number) => void;
  enqueue: (track: PlayerTrack) => void;
  removeFromQueue: (id: number) => void;
  clearQueue: () => void;
  next: () => void;
  setRate: (r: number) => void;
  close: () => void;
}

export const usePlayer = create<PlayerState>()(
  persist(
    (set, get) => ({
      current: null,
      queue: [],
      positions: {},
      rate: 1,
      playing: false,
      seekTo: null,
      play: (track, startAt) => {
        const at = startAt ?? get().positions[track.id] ?? 0;
        set((s) => ({
          current: track,
          playing: true,
          seekTo: at,
          queue: s.queue.filter((q) => q.id !== track.id),
        }));
      },
      setPlaying: (playing) => set({ playing }),
      requestSeek: (t) => set({ seekTo: Math.max(0, t) }),
      clearSeek: () => set({ seekTo: null }),
      rememberPosition: (id, t) => set((s) => ({ positions: { ...s.positions, [id]: Math.round(t) } })),
      enqueue: (track) =>
        set((s) => {
          if (s.current?.id === track.id || s.queue.some((q) => q.id === track.id)) return s;
          return { queue: [...s.queue, track] };
        }),
      removeFromQueue: (id) => set((s) => ({ queue: s.queue.filter((q) => q.id !== id) })),
      clearQueue: () => set({ queue: [] }),
      next: () => {
        const [head, ...rest] = get().queue;
        if (!head) {
          set({ playing: false });
          return;
        }
        set((s) => ({ current: head, queue: rest, playing: true, seekTo: s.positions[head.id] ?? 0 }));
      },
      setRate: (rate) => set({ rate }),
      close: () => set({ current: null, playing: false, seekTo: null }),
    }),
    {
      name: 'howlers-player',
      version: 1,
      storage: createJSONStorage(() => {
        try {
          return window.localStorage;
        } catch {
          return sessionStorage;
        }
      }),
      // Never resume playing automatically after a reload: browsers block it and it surprises people.
      partialize: (s) => ({ current: s.current, queue: s.queue, positions: s.positions, rate: s.rate }),
    },
  ),
);

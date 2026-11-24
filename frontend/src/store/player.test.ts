import { beforeEach, describe, expect, it } from 'vitest';
import { usePlayer, type PlayerTrack } from './player';

const track = (id: number): PlayerTrack => ({
  id,
  title: `Episode ${id}`,
  communitySlug: 'programming',
  thumbnailUrl: null,
  mediaUrl: `/api/v1/files/podcasts/x${id}.mp3`,
  durationSeconds: 3000,
  chapters: [],
});

describe('player store', () => {
  beforeEach(() => usePlayer.setState({ current: null, queue: [], positions: {}, playing: false, seekTo: null, rate: 1 }));

  it('queues without duplicates and skips the current track', () => {
    const s = usePlayer.getState();
    s.play(track(1));
    s.enqueue(track(1));
    s.enqueue(track(2));
    s.enqueue(track(2));
    s.enqueue(track(3));
    expect(usePlayer.getState().queue.map((t) => t.id)).toEqual([2, 3]);
  });

  it('advances to the next queued track and resumes from its remembered position', () => {
    const s = usePlayer.getState();
    s.play(track(1));
    s.enqueue(track(2));
    s.rememberPosition(2, 120.4);
    s.next();
    const st = usePlayer.getState();
    expect(st.current?.id).toBe(2);
    expect(st.seekTo).toBe(120);
    expect(st.queue).toEqual([]);
    st.next();
    expect(usePlayer.getState().playing).toBe(false);
  });

  it('playing a queued track removes it from the queue; remove/clear work', () => {
    const s = usePlayer.getState();
    s.enqueue(track(1));
    s.enqueue(track(2));
    s.enqueue(track(3));
    s.play(track(2), 30);
    expect(usePlayer.getState().queue.map((t) => t.id)).toEqual([1, 3]);
    expect(usePlayer.getState().seekTo).toBe(30);
    s.removeFromQueue(1);
    expect(usePlayer.getState().queue.map((t) => t.id)).toEqual([3]);
    s.clearQueue();
    expect(usePlayer.getState().queue).toEqual([]);
  });
});

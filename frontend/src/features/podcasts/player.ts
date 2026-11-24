import { create } from 'zustand';
import { useAuthStore } from '@/store/authStore';
import { usePlayer, type PlayerTrack } from '@/store/player';
import type { EpisodeDetail } from '@/types/podcasts';
import { podcastApi } from './api';

/** Live playback clock (not persisted). Updated by the single audio engine a few times per second. */
interface Clock {
  time: number;
  duration: number;
  buffered: number;
  set: (p: Partial<Omit<Clock, 'set'>>) => void;
}
export const usePlayerClock = create<Clock>((set) => ({ time: 0, duration: 0, buffered: 0, set: (p) => set(p) }));

export function trackFromEpisode(e: EpisodeDetail): PlayerTrack | null {
  if (!e.mediaUrl || e.mediaKind !== 'AUDIO') return null;
  return {
    id: e.id,
    title: e.title,
    communitySlug: e.community.slug,
    thumbnailUrl: e.thumbnailUrl,
    mediaUrl: e.mediaUrl,
    durationSeconds: e.durationSeconds,
    chapters: e.chapters.map((c) => ({ startSeconds: c.startSeconds, title: c.title })),
    aiVoice: e.audioOrigin === 'AI_TTS',
  };
}

/**
 * Start an episode in the global player, resuming from the server position (or this device's),
 * unless the episode was finished, in which case it starts over.
 */
export function playEpisode(e: EpisodeDetail, startAt?: number) {
  const track = trackFromEpisode(e);
  if (!track) return false;
  const local = usePlayer.getState().positions[e.id];
  const server = e.progress && !e.progress.completed ? e.progress.positionSeconds : undefined;
  usePlayer.getState().play(track, startAt ?? server ?? (e.progress?.completed ? 0 : local) ?? 0);
  return true;
}

/** Throttled progress writer shared by the audio engine and the inline video player. */
export function createProgressSaver(episodeId: number) {
  let lastSent = -1;
  let lastAt = 0;
  return (position: number, opts: { force?: boolean; completed?: boolean } = {}) => {
    usePlayer.getState().rememberPosition(episodeId, position);
    if (!useAuthStore.getState().accessToken) return;
    const now = Date.now();
    const rounded = Math.round(position);
    if (!opts.force && !opts.completed && (now - lastAt < 15_000 || rounded === lastSent)) return;
    lastSent = rounded;
    lastAt = now;
    podcastApi.progress(episodeId, rounded, opts.completed).catch(() => undefined);
  };
}

/** An episode counts as finished in its last 30 seconds (or last 2%, for short clips). */
export function isNearEnd(time: number, duration: number) {
  return duration > 0 && time >= duration - Math.max(2, Math.min(30, duration * 0.02));
}

import * as DM from '@radix-ui/react-dropdown-menu';
import { ChevronUp, ListMusic, Pause, Play, Redo2, SkipForward, Undo2, X } from 'lucide-react';
import { useEffect, useMemo, useRef, type ChangeEvent } from 'react';
import { Link } from 'react-router-dom';
import { Menu, MenuContent, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/Menu';
import { toast } from '@/components/ui/Toast';
import { RATES, usePlayer } from '@/store/player';
import { cn } from '@/utils/format';
import { saveProgressKeepalive } from '../api';
import { AiVoiceBadge } from './AiVoiceBadge';
import { activeIndex, formatClock } from '../helpers';
import { createProgressSaver, isNearEnd, usePlayerClock } from '../player';

/**
 * The one <audio> element in the app. It lives in AppLayout, so audio keeps playing while the
 * user navigates. Video episodes play inline on their page instead.
 */
export function MiniPlayer() {
  const audio = useRef<HTMLAudioElement>(null);
  const { current, queue, playing, rate, seekTo } = usePlayer();
  const p = usePlayer.getState;
  const { time, duration, buffered } = usePlayerClock();
  const saver = useMemo(() => (current ? createProgressSaver(current.id) : null), [current?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Load a new source when the track changes.
  useEffect(() => {
    const el = audio.current;
    if (!el || !current) return;
    if (el.src !== new URL(current.mediaUrl, window.location.origin).href) {
      el.src = current.mediaUrl;
      // A track restored after a reload has no pending seek, so resume from this device's last position.
      const start = p().seekTo ?? p().positions[current.id] ?? 0;
      if (p().seekTo == null && start > 0) p().requestSeek(start);
      usePlayerClock.getState().set({ time: start, duration: current.durationSeconds ?? 0, buffered: 0 });
    }
  }, [current, p]);

  // Apply seek requests.
  useEffect(() => {
    const el = audio.current;
    if (!el || seekTo == null) return;
    const apply = () => {
      el.currentTime = seekTo;
      usePlayerClock.getState().set({ time: seekTo });
      p().clearSeek();
    };
    if (el.readyState >= 1) apply();
    else el.addEventListener('loadedmetadata', apply, { once: true });
  }, [seekTo, current?.id, p]);

  // Play/pause follows the store.
  useEffect(() => {
    const el = audio.current;
    if (!el || !current) return;
    if (playing && el.paused) {
      el.play().catch(() => {
        p().setPlaying(false);
        toast.info('Press play to start listening');
      });
    } else if (!playing && !el.paused) {
      el.pause();
    }
  }, [playing, current, p]);

  useEffect(() => {
    if (audio.current) audio.current.playbackRate = rate;
  }, [rate, current?.id]);

  // Save on unload.
  useEffect(() => {
    const onUnload = () => {
      const el = audio.current;
      const c = p().current;
      if (el && c && el.currentTime > 0) {
        p().rememberPosition(c.id, el.currentTime);
        saveProgressKeepalive(c.id, el.currentTime);
      }
    };
    window.addEventListener('pagehide', onUnload);
    return () => window.removeEventListener('pagehide', onUnload);
  }, [p]);

  const chapterTitle = useMemo(() => {
    if (!current?.chapters.length) return null;
    const i = activeIndex(current.chapters, time, (c) => c.startSeconds);
    return i >= 0 ? current.chapters[i].title : null;
  }, [current, time]);

  // Media Session: lock-screen / headset controls and metadata.
  useEffect(() => {
    if (!current || !('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    try {
      ms.metadata = new MediaMetadata({
        title: current.title,
        artist: `c/${current.communitySlug}`,
        album: chapterTitle ?? 'Howlers podcasts',
        artwork: current.thumbnailUrl ? [{ src: new URL(current.thumbnailUrl, window.location.origin).href, sizes: '512x512' }] : [],
      });
      const el = () => audio.current;
      ms.setActionHandler('play', () => p().setPlaying(true));
      ms.setActionHandler('pause', () => p().setPlaying(false));
      ms.setActionHandler('seekbackward', () => el() && p().requestSeek(el()!.currentTime - 15));
      ms.setActionHandler('seekforward', () => el() && p().requestSeek(el()!.currentTime + 30));
      ms.setActionHandler('seekto', (d) => d.seekTime != null && p().requestSeek(d.seekTime));
      ms.setActionHandler('nexttrack', p().queue.length ? () => p().next() : null);
    } catch {
      /* partially supported browsers */
    }
  }, [current, chapterTitle, queue.length, p]);

  // eslint-disable-next-line jsx-a11y/media-has-caption -- hidden and idle: nothing is playing
  if (!current) return <audio ref={audio} preload="metadata" hidden />;

  const total = duration || current.durationSeconds || 0;
  const pct = total ? (time / total) * 100 : 0;
  const bufPct = total ? (buffered / total) * 100 : 0;
  const onSeek = (e: ChangeEvent<HTMLInputElement>) => {
    const t = Number(e.target.value);
    p().requestSeek(t);
    saver?.(t, { force: true });
  };

  return (
    <div
      className="fixed inset-x-0 bottom-14 z-30 border-t border-border bg-surface/95 backdrop-blur md:bottom-0 md:left-16 xl:left-[272px]"
      role="region"
      aria-label="Podcast player"
    >
      {/* eslint-disable-next-line jsx-a11y/media-has-caption -- audio-only player; every published episode has a timed transcript on its episode page (publishing requires one) */}
      <audio
        ref={audio}
        preload="metadata"
        onTimeUpdate={(e) => {
          const el = e.currentTarget;
          const b = el.buffered.length ? el.buffered.end(el.buffered.length - 1) : 0;
          usePlayerClock.getState().set({ time: el.currentTime, buffered: b });
          if (!el.paused) saver?.(el.currentTime);
        }}
        onDurationChange={(e) => Number.isFinite(e.currentTarget.duration) && usePlayerClock.getState().set({ duration: e.currentTarget.duration })}
        onPlay={() => p().setPlaying(true)}
        onPause={(e) => {
          p().setPlaying(false);
          if (e.currentTarget.currentTime > 0) p().rememberPosition(current.id, e.currentTarget.currentTime);
          saver?.(e.currentTarget.currentTime, { force: true });
        }}
        onEnded={(e) => {
          saver?.(e.currentTarget.duration || e.currentTarget.currentTime, { completed: true });
          p().rememberPosition(current.id, 0);
          p().next();
        }}
        onSeeked={(e) => {
          const el = e.currentTarget;
          if (isNearEnd(el.currentTime, el.duration)) saver?.(el.currentTime, { completed: true });
        }}
        hidden
      />
      {/* thin progress line for quick orientation */}
      <div className="relative h-1 bg-surface-3" aria-hidden="true">
        <div className="absolute inset-y-0 left-0 bg-text/20" style={{ width: `${bufPct}%` }} />
        <div className="absolute inset-y-0 left-0 bg-brand" style={{ width: `${pct}%` }} />
      </div>
      <div className="mx-auto flex max-w-[1200px] items-center gap-3 px-3 py-2">
        <Link to={`/podcasts/${current.id}`} className="flex min-w-0 flex-1 items-center gap-3 sm:flex-none sm:basis-64" title="Open episode">
          {current.thumbnailUrl ? (
            <img src={current.thumbnailUrl} alt="" className="h-10 w-10 shrink-0 rounded-lg object-cover" />
          ) : (
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-brand/15 text-brand"><ListMusic size={18} /></span>
          )}
          <span className="min-w-0">
            <span className="flex min-w-0 items-center gap-1.5">
              <span className="truncate text-sm font-semibold">{current.title}</span>
              {current.aiVoice && <AiVoiceBadge size="xs" />}
            </span>
            <span className="block truncate text-xs text-muted">{chapterTitle ?? `c/${current.communitySlug}`}</span>
          </span>
        </Link>

        <div className="flex items-center gap-1">
          <button type="button" aria-label="Back 15 seconds" onClick={() => p().requestSeek(time - 15)} className="hidden h-9 w-9 items-center justify-center rounded-full text-muted hover:bg-surface-2 hover:text-text sm:inline-flex">
            <Undo2 size={17} />
          </button>
          <button
            type="button"
            aria-label={playing ? 'Pause' : 'Play'}
            onClick={() => p().setPlaying(!playing)}
            className="inline-flex h-10 w-10 items-center justify-center rounded-full bg-text text-bg hover:opacity-90"
          >
            {playing ? <Pause size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" className="translate-x-px" />}
          </button>
          <button type="button" aria-label="Forward 30 seconds" onClick={() => p().requestSeek(time + 30)} className="hidden h-9 w-9 items-center justify-center rounded-full text-muted hover:bg-surface-2 hover:text-text sm:inline-flex">
            <Redo2 size={17} />
          </button>
        </div>

        <div className="hidden min-w-0 flex-1 items-center gap-2 md:flex">
          <span className="w-14 text-right text-xs tabular-nums text-muted">{formatClock(time)}</span>
          <input
            type="range"
            min={0}
            max={Math.max(1, Math.floor(total))}
            step={1}
            value={Math.min(Math.floor(time), Math.floor(total) || 0)}
            onChange={onSeek}
            aria-label="Seek"
            aria-valuetext={`${formatClock(time)} of ${formatClock(total)}`}
            className="h-1 flex-1 cursor-pointer accent-[rgb(var(--brand))]"
          />
          <span className="w-14 text-xs tabular-nums text-muted">{formatClock(total)}</span>
        </div>

        <Menu>
          <MenuTrigger aria-label={`Playback speed ${rate}x`} className="hidden h-8 rounded-full px-2.5 text-xs font-bold text-muted hover:bg-surface-2 hover:text-text sm:inline-flex sm:items-center">
            {rate}x
          </MenuTrigger>
          <MenuContent align="end">
            <MenuLabel>Playback speed</MenuLabel>
            {RATES.map((r) => (
              <DM.Item key={r} onSelect={() => p().setRate(r)} className={cn('cursor-pointer rounded-md px-2.5 py-1.5 text-sm outline-none data-[highlighted]:bg-surface-2', r === rate && 'font-bold text-brand')}>
                {r}x
              </DM.Item>
            ))}
          </MenuContent>
        </Menu>

        <Menu>
          <MenuTrigger aria-label={`Queue (${queue.length})`} className="relative inline-flex h-9 w-9 items-center justify-center rounded-full text-muted hover:bg-surface-2 hover:text-text">
            <ChevronUp size={18} />
            {queue.length > 0 && <span className="absolute -right-0.5 -top-0.5 rounded-full bg-brand px-1 text-[10px] font-bold text-brand-fg">{queue.length}</span>}
          </MenuTrigger>
          <MenuContent align="end">
            <MenuLabel>Up next</MenuLabel>
            {queue.length === 0 && <p className="px-2.5 py-2 text-sm text-faint">Your queue is empty.</p>}
            {queue.map((q) => (
              <div key={q.id} className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-sm">
                <span className="min-w-0 flex-1 truncate">{q.title}</span>
                <button type="button" aria-label={`Remove ${q.title} from queue`} onClick={() => p().removeFromQueue(q.id)} className="rounded-full p-1 text-muted hover:bg-surface-2 hover:text-text">
                  <X size={13} />
                </button>
              </div>
            ))}
            {queue.length > 0 && (
              <>
                <MenuSeparator />
                <DM.Item onSelect={() => p().next()} className="flex cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-sm outline-none data-[highlighted]:bg-surface-2">
                  <SkipForward size={15} /> Play next
                </DM.Item>
                <DM.Item onSelect={() => p().clearQueue()} className="cursor-pointer rounded-md px-2.5 py-2 text-sm text-muted outline-none data-[highlighted]:bg-surface-2">
                  Clear queue
                </DM.Item>
              </>
            )}
          </MenuContent>
        </Menu>

        <button
          type="button"
          aria-label="Close player"
          onClick={() => {
            const el = audio.current;
            if (el && el.currentTime > 0) saver?.(el.currentTime, { force: true });
            el?.pause();
            p().close();
          }}
          className="inline-flex h-9 w-9 items-center justify-center rounded-full text-muted hover:bg-surface-2 hover:text-text"
        >
          <X size={17} />
        </button>
      </div>
    </div>
  );
}

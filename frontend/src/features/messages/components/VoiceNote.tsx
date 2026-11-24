import { useQueryClient } from '@tanstack/react-query';
import { Pause, Play } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent } from 'react';
import type { DmMedia } from '@/types/messages';
import { cn } from '@/utils/format';
import { dmKeys } from '../api';
import { formatDuration, seekPosition, waveformBars } from '../chat';

/** Only one voice note plays at a time, like any chat app. */
let playing: HTMLAudioElement | null = null;

const BAR_COUNT = 30;

/**
 * A voice note bubble: play/pause, a clickable waveform-style progress bar and elapsed / total time. Signed URLs
 * expire, so a load error refetches the thread once for a fresh URL (the same way DM images do).
 */
export function VoiceNote({ media, conversationId, tone }: { media: DmMedia; conversationId: number; tone: 'out' | 'in' }) {
  const qc = useQueryClient();
  const audio = useRef<HTMLAudioElement>(null);
  const retried = useRef(false);
  const resumeAt = useRef<number | null>(null);
  const [isPlaying, setPlaying] = useState(false);
  const [pos, setPos] = useState(0);
  const [loadedMs, setLoadedMs] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const bars = useMemo(() => waveformBars(media.id, BAR_COUNT), [media.id]);
  // MediaRecorder WebM files often report an Infinite duration, so the uploader's figure wins.
  const total = media.durationMs ?? loadedMs ?? 0;
  const fraction = total > 0 ? Math.min(1, pos / total) : 0;

  useEffect(() => {
    const el = audio.current;
    return () => {
      if (el && playing === el) playing = null;
      el?.pause();
    };
  }, []);

  // A fresh URL after the refetch clears the error state (onLoadedMetadata resumes the position).
  useEffect(() => {
    setFailed(false);
  }, [media.url]);

  const toggle = async () => {
    const el = audio.current;
    if (!el) return;
    if (!el.paused) {
      el.pause();
      return;
    }
    if (playing && playing !== el) playing.pause();
    playing = el;
    try {
      await el.play();
    } catch {
      // Autoplay rules or a stale URL: onError handles the latter.
    }
  };

  const seekTo = (ms: number) => {
    const el = audio.current;
    if (!el || total <= 0) return;
    const clamped = Math.max(0, Math.min(total, ms));
    try {
      el.currentTime = clamped / 1000;
    } catch {
      // Not seekable yet.
    }
    setPos(clamped);
  };

  const onBarClick = (e: MouseEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    seekTo(seekPosition(e.clientX - r.left, r.width, total));
  };
  const onBarKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = 5_000;
    if (e.key === 'ArrowRight' || e.key === 'ArrowUp') seekTo(pos + step);
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') seekTo(pos - step);
    else if (e.key === 'Home') seekTo(0);
    else if (e.key === 'End') seekTo(total);
    else return;
    e.preventDefault();
  };

  const out = tone === 'out';
  return (
    <div className="flex w-60 max-w-full items-center gap-2.5 py-1 sm:w-64">
      {/* eslint-disable-next-line jsx-a11y/media-has-caption -- a user's recorded voice note: there is no transcript to caption it with; the controls below are fully labelled */}
      <audio
        ref={audio}
        src={media.url}
        preload="metadata"
        onLoadedMetadata={(e) => {
          const d = e.currentTarget.duration;
          if (Number.isFinite(d) && d > 0) setLoadedMs(d * 1000);
          if (resumeAt.current != null) {
            e.currentTarget.currentTime = resumeAt.current / 1000;
            resumeAt.current = null;
          }
        }}
        onTimeUpdate={(e) => setPos(e.currentTarget.currentTime * 1000)}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={(e) => {
          setPlaying(false);
          setPos(0);
          e.currentTarget.currentTime = 0;
          if (playing === e.currentTarget) playing = null;
        }}
        onError={() => {
          setPlaying(false);
          if (media.signed && !retried.current) {
            retried.current = true;
            resumeAt.current = pos;
            void qc.invalidateQueries({ queryKey: dmKeys.messages(conversationId) });
            return;
          }
          setFailed(true);
        }}
      />
      <button
        type="button"
        onClick={() => void toggle()}
        aria-label={isPlaying ? 'Pause voice message' : 'Play voice message'}
        className={cn(
          'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full shadow-sm transition-transform active:scale-95',
          out ? 'bg-[color:var(--ct-out-fg)] text-[color:var(--ct-out-1)]' : 'chat-accent',
        )}
      >
        {isPlaying ? <Pause size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" className="translate-x-px" />}
      </button>
      <div className="min-w-0 flex-1">
        <div
          role="slider"
          tabIndex={0}
          aria-label="Voice message position"
          aria-valuemin={0}
          aria-valuemax={Math.round(total / 1000)}
          aria-valuenow={Math.round(pos / 1000)}
          aria-valuetext={`${formatDuration(pos)} of ${formatDuration(total)}`}
          onClick={onBarClick}
          onKeyDown={onBarKey}
          className="flex h-8 cursor-pointer items-center gap-[2px] rounded outline-none focus-visible:ring-2 focus-visible:ring-current"
        >
          {bars.map((h, i) => (
            <span
              key={i}
              aria-hidden="true"
              className={cn('block flex-1 rounded-full bg-current transition-opacity', (i + 0.5) / bars.length <= fraction ? 'opacity-100' : 'opacity-35')}
              style={{ height: `${Math.round(h * 100)}%` }}
            />
          ))}
        </div>
        <p className="mt-0.5 text-[11px] tabular-nums opacity-80">
          {failed ? 'Couldn’t load this voice message' : isPlaying || pos > 0 ? `${formatDuration(pos)} / ${formatDuration(total)}` : formatDuration(total)}
        </p>
      </div>
    </div>
  );
}

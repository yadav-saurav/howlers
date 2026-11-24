import { Pause, Play, Search } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Skeleton } from '@/components/ui/Display';
import { ErrorState } from '@/components/ui/States';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { cn } from '@/utils/format';
import { useTranscript } from '../api';
import { activeSegmentIndex, formatClock } from '../helpers';

/**
 * Searchable transcript. Clicking a line jumps there; while the episode plays, the active line is
 * highlighted and (unless the reader paused it, or prefers reduced motion) kept in view.
 */
export function TranscriptPanel({ episodeId, time, active, onJump }: { episodeId: number; time: number; active: boolean; onJump: (t: number) => void }) {
  const [q, setQ] = useState('');
  const query = useDebouncedValue(q.trim(), 250);
  const segs = useTranscript(episodeId, query, true);
  const [follow, setFollow] = useState(() => !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
  const box = useRef<HTMLDivElement>(null);
  const items = useMemo(() => segs.data ?? [], [segs.data]);
  const current = useMemo(() => (active && !query ? activeSegmentIndex(items, time) : -1), [items, time, active, query]);

  useEffect(() => {
    if (!follow || current < 0 || !box.current) return;
    const el = box.current.querySelector<HTMLElement>(`[data-seg="${current}"]`);
    if (!el) return;
    const top = el.offsetTop - box.current.clientHeight / 3;
    box.current.scrollTo({ top, behavior: 'smooth' });
  }, [current, follow]);

  return (
    <section aria-labelledby="transcript-h" className="rounded-2xl border border-border">
      <div className="flex flex-wrap items-center gap-2 border-b border-border p-3">
        <h2 id="transcript-h" className="mr-auto text-sm font-bold">Transcript</h2>
        <div className="relative">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search transcript" aria-label="Search transcript" className="h-8 w-52 rounded-full bg-surface-2 pl-8 pr-3 text-sm placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-brand/30" />
        </div>
        <button type="button" onClick={() => setFollow((f) => !f)} aria-pressed={follow} className="inline-flex h-8 items-center gap-1.5 rounded-full bg-surface-2 px-3 text-xs font-semibold text-muted hover:text-text">
          {follow ? <Pause size={12} /> : <Play size={12} />} {follow ? 'Auto-scroll on' : 'Auto-scroll off'}
        </button>
      </div>
      <div ref={box} className="relative max-h-[420px] overflow-y-auto p-2">
        {segs.isPending && <div className="space-y-2 p-2"><Skeleton className="h-4" /><Skeleton className="h-4 w-5/6" /><Skeleton className="h-4 w-4/6" /></div>}
        {segs.isError && <ErrorState error={segs.error} onRetry={() => segs.refetch()} />}
        {segs.data && items.length === 0 && <p className="p-3 text-sm text-muted">{query ? `No lines mention “${query}”.` : 'No transcript yet.'}</p>}
        {items.map((s, i) => (
          <button
            key={s.id}
            type="button"
            data-seg={i}
            onClick={() => onJump(s.startSeconds)}
            aria-current={i === current ? 'true' : undefined}
            className={cn('flex w-full gap-3 rounded-lg px-2 py-1.5 text-left text-sm transition-colors hover:bg-surface-2', i === current && 'bg-brand/15')}
          >
            <span className="w-14 shrink-0 pt-0.5 text-xs tabular-nums text-brand">{formatClock(s.startSeconds)}</span>
            <span className="min-w-0">
              {s.speaker && <span className="mr-1.5 font-semibold">{s.speaker}:</span>}
              <span className="text-text/90">{s.text}</span>
            </span>
          </button>
        ))}
      </div>
    </section>
  );
}

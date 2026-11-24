import { Clapperboard, Headphones, Play } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/Display';
import { AiVoiceBadge } from './AiVoiceBadge';
import type { EpisodeSummary } from '@/types/podcasts';
import { cn, compactNumber, timeAgo } from '@/utils/format';
import { STATUS_LABEL, formatDuration, progressPercent, typeLabel } from '../helpers';

function Art({ e, className }: { e: EpisodeSummary; className?: string }) {
  const Icon = e.mediaKind === 'VIDEO' ? Clapperboard : Headphones;
  return e.thumbnailUrl ? (
    <img src={e.thumbnailUrl} alt="" loading="lazy" className={cn('shrink-0 rounded-xl object-cover', className)} />
  ) : (
    <span className={cn('flex shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-brand/30 to-accent/20 text-brand', className)}>
      <Icon size={26} />
    </span>
  );
}

function ProgressBar({ e }: { e: EpisodeSummary }) {
  const pct = e.progress?.completed ? 100 : progressPercent(e.progress?.positionSeconds, e.durationSeconds);
  if (!pct) return null;
  return (
    <div className="mt-2 h-1 overflow-hidden rounded-full bg-surface-3" role="progressbar" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100} aria-label="Listened">
      <div className={cn('h-full', e.progress?.completed ? 'bg-accent' : 'bg-brand')} style={{ width: `${pct}%` }} />
    </div>
  );
}

/** Row layout for lists. `studio` links to the editor and shows status instead of play count. */
export function EpisodeRow({ e, studio }: { e: EpisodeSummary; studio?: boolean }) {
  const to = studio ? `/c/${e.community.slug}/podcasts/studio/${e.id}` : `/podcasts/${e.id}`;
  return (
    <Link to={to} className="group flex gap-3 rounded-2xl p-2 transition-colors hover:bg-surface-2/60">
      <Art e={e} className="h-20 w-20 sm:h-24 sm:w-24" />
      <div className="min-w-0 flex-1 py-0.5">
        <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted">
          <span className="font-semibold text-text">c/{e.community.slug}</span>
          <span>•</span>
          <span>{typeLabel(e.type)}</span>
          {e.audioOrigin === 'AI_TTS' && <AiVoiceBadge size="xs" />}
          {studio && <Badge tone={e.status === 'PUBLISHED' ? 'accent' : e.status === 'IN_REVIEW' ? 'warning' : 'neutral'} className="rounded-full px-2">{STATUS_LABEL[e.status]}</Badge>}
        </div>
        <h3 className="mt-0.5 line-clamp-2 font-semibold leading-snug group-hover:underline">{e.title}</h3>
        {e.summary && <p className="mt-0.5 line-clamp-1 text-sm text-muted">{e.summary}</p>}
        <div className="mt-1 flex flex-wrap items-center gap-x-2 text-xs text-faint">
          <span>{formatDuration(e.durationSeconds)}</span>
          {e.chapterCount > 0 && <span>· {e.chapterCount} chapters</span>}
          {!studio && <span>· {compactNumber(e.playCount)} plays</span>}
          <span>· {timeAgo(e.publishedAt ?? e.createdAt)}</span>
        </div>
        <ProgressBar e={e} />
      </div>
    </Link>
  );
}

/** Tile layout for horizontal shelves. */
export function EpisodeTile({ e }: { e: EpisodeSummary }) {
  return (
    <Link to={`/podcasts/${e.id}`} className="group block w-[180px] shrink-0 sm:w-[200px]">
      <div className="relative">
        <Art e={e} className="aspect-square h-auto w-full" />
        <span className="absolute bottom-2 right-2 flex h-9 w-9 items-center justify-center rounded-full bg-text text-bg opacity-0 shadow-float transition-opacity group-hover:opacity-100" aria-hidden="true">
          <Play size={16} fill="currentColor" />
        </span>
      </div>
      <div className="mt-2 text-xs text-muted">c/{e.community.slug} · {formatDuration(e.durationSeconds)}</div>
      <div className="line-clamp-2 text-sm font-semibold leading-snug group-hover:underline">{e.title}</div>
      <ProgressBar e={e} />
    </Link>
  );
}

/** Big featured card. */
export function EpisodeFeature({ e }: { e: EpisodeSummary }) {
  return (
    <Link to={`/podcasts/${e.id}`} className="group flex flex-col gap-4 rounded-2xl border border-border bg-gradient-to-br from-brand/10 to-transparent p-4 sm:flex-row sm:items-center">
      <Art e={e} className="h-40 w-40 self-start" />
      <div className="min-w-0">
        <span className="flex flex-wrap items-center gap-1.5">
          <Badge tone="info" className="rounded-full px-2">{typeLabel(e.type)}</Badge>
          {e.audioOrigin === 'AI_TTS' && <AiVoiceBadge />}
        </span>
        <h3 className="mt-2 text-xl font-bold leading-tight group-hover:underline">{e.title}</h3>
        {e.summary && <p className="mt-1 line-clamp-3 text-sm text-muted">{e.summary}</p>}
        <p className="mt-2 text-xs text-faint">c/{e.community.slug} · {formatDuration(e.durationSeconds)} · {compactNumber(e.playCount)} plays</p>
      </div>
    </Link>
  );
}

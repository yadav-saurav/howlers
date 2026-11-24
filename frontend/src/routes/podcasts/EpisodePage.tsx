import { Bookmark, BookmarkCheck, Download, FileX, Headphones, ListPlus, MessageSquare, Pause, Pencil, Play, Share2 } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { ActionPill } from '@/features/posts/components/ActionPill';
import { useEpisode, useSaveEpisode } from '@/features/podcasts/api';
import { TranscriptPanel } from '@/features/podcasts/components/TranscriptPanel';
import { AiVoiceBadge } from '@/features/podcasts/components/AiVoiceBadge';
import { aiVoiceDescription } from '@/features/podcasts/audioGeneration';
import { activeIndex, formatClock, formatDuration, typeLabel } from '@/features/podcasts/helpers';
import { createProgressSaver, isNearEnd, playEpisode, trackFromEpisode, usePlayerClock } from '@/features/podcasts/player';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { ApiError, errorMessage } from '@/services/apiError';
import { usePlayer } from '@/store/player';
import { absoluteUrl, cn, compactNumber, formatDate } from '@/utils/format';

/** Episode page: audio hands off to the global player; video plays inline with subtitles. */
export default function EpisodePage() {
  const { id: raw = '' } = useParams();
  const id = Number(raw);
  const q = useEpisode(id);
  const { isAuthed } = useAuth();
  const save = useSaveEpisode(id);
  const player = usePlayer();
  const clock = usePlayerClock();
  const video = useRef<HTMLVideoElement>(null);
  const [videoTime, setVideoTime] = useState(0);
  const videoSaver = useMemo(() => createProgressSaver(id), [id]);
  useDocumentTitle(q.data?.title ?? 'Episode');

  if (!Number.isFinite(id)) return <EmptyState icon={FileX} title="Episode not found" />;
  if (q.isPending) {
    return (
      <div className="mx-auto max-w-[1000px] space-y-4 px-3 py-6 sm:px-6">
        <div className="flex gap-4"><Skeleton className="h-40 w-40 rounded-2xl" /><div className="flex-1 space-y-3"><Skeleton className="h-6 w-2/3" /><Skeleton className="h-4 w-1/2" /></div></div>
        <Skeleton className="h-64 rounded-2xl" />
      </div>
    );
  }
  if (q.isError) {
    const status = q.error instanceof ApiError ? q.error.status : 0;
    return status === 404 || status === 403 ? (
      <EmptyState icon={FileX} title="This episode isn't available" description="It may be unpublished, or in a private community." action={<Link to="/podcasts" className={buttonClasses('primary', 'sm')}>Browse podcasts</Link>} />
    ) : (
      <div className="p-6"><ErrorState error={q.error} onRetry={() => q.refetch()} /></div>
    );
  }

  const e = q.data;
  const isVideo = e.mediaKind === 'VIDEO';
  const isCurrent = player.current?.id === e.id;
  const time = isVideo ? videoTime : isCurrent ? clock.time : (e.progress?.positionSeconds ?? 0);
  const chapterIdx = activeIndex(e.chapters, time, (c) => c.startSeconds);
  const playing = isCurrent && player.playing;

  const jump = (t: number) => {
    if (isVideo) {
      if (video.current) {
        video.current.currentTime = t;
        void video.current.play().catch(() => undefined);
      }
    } else if (isCurrent) {
      player.requestSeek(t);
      player.setPlaying(true);
    } else {
      playEpisode(e, t);
    }
  };

  const toggleAudio = () => {
    if (isCurrent) player.setPlaying(!player.playing);
    else if (!playEpisode(e)) toast.error('This episode has no audio yet');
  };

  return (
    <div className="mx-auto max-w-[1000px] px-3 py-6 sm:px-6">
      <header className="flex flex-col gap-5 sm:flex-row">
        {e.thumbnailUrl ? (
          <img src={e.thumbnailUrl} alt="" className="h-44 w-44 shrink-0 rounded-2xl object-cover" />
        ) : (
          <span className="flex h-44 w-44 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-brand/30 to-accent/20 text-brand"><Headphones size={48} /></span>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
            <Link to={`/c/${e.community.slug}`} className="inline-flex items-center gap-1.5 font-semibold text-text hover:underline">
              <Avatar src={e.community.iconUrl} name={e.community.slug} size="xs" /> c/{e.community.slug}
            </Link>
            <Badge tone="info" className="rounded-full px-2">{typeLabel(e.type)}</Badge>
            {e.audioOrigin === 'AI_TTS' && <AiVoiceBadge provider={e.audioProvider} model={e.audioModel} />}
            {e.status !== 'PUBLISHED' && <Badge tone="warning" className="rounded-full px-2">{e.status === 'IN_REVIEW' ? 'In review' : e.status === 'DRAFT' ? 'Draft' : 'Archived'}</Badge>}
          </div>
          <h1 className="mt-2 text-2xl font-bold leading-tight sm:text-[28px]">{e.title}</h1>
          <p className="mt-1 text-sm text-faint">
            {formatDuration(e.durationSeconds)}
            {e.publishedAt && <> · {formatDate(e.publishedAt)}</>} · {compactNumber(e.playCount)} plays
            {e.chapters.length > 0 && <> · {e.chapters.length} chapters</>}
          </p>
          {e.summary && <p className="mt-3 whitespace-pre-line text-[15px] leading-relaxed text-text/90">{e.summary}</p>}

          <div className="mt-4 flex flex-wrap items-center gap-2">
            {!isVideo && e.mediaUrl && (
              <button type="button" onClick={toggleAudio} className={buttonClasses('primary', 'md')}>
                {playing ? <Pause size={16} fill="currentColor" /> : <Play size={16} fill="currentColor" />}
                {playing ? 'Pause' : isCurrent || (e.progress && !e.progress.completed && e.progress.positionSeconds > 5) ? `Resume from ${formatClock(isCurrent ? clock.time : e.progress?.positionSeconds)}` : 'Play episode'}
              </button>
            )}
            {!isVideo && e.mediaUrl && !isCurrent && (
              <ActionPill
                onClick={() => {
                  const t = trackFromEpisode(e);
                  if (t) {
                    player.enqueue(t);
                    toast.success('Added to your queue');
                  }
                }}
              >
                <ListPlus size={15} /> Queue
              </ActionPill>
            )}
            {isAuthed && (
              <ActionPill active={e.saved} pressed={e.saved} onClick={() => save.mutate(!e.saved, { onError: (er) => toast.error(errorMessage(er)) })}>
                {e.saved ? <BookmarkCheck size={15} /> : <Bookmark size={15} />} {e.saved ? 'Saved' : 'Save'}
              </ActionPill>
            )}
            <ActionPill onClick={() => navigator.clipboard.writeText(absoluteUrl(`/podcasts/${e.id}`)).then(() => toast.success('Link copied'), () => toast.error('Could not copy the link'))}>
              <Share2 size={15} /> Share
            </ActionPill>
            {e.allowDownload && e.mediaUrl && (
              <a href={e.mediaUrl} download className="inline-flex h-8 items-center gap-1.5 rounded-full bg-surface-2 px-3 text-xs font-semibold hover:bg-surface-3">
                <Download size={15} /> Download
              </a>
            )}
            {e.viewer.canManage && (
              <Link to={`/c/${e.community.slug}/podcasts/studio/${e.id}`} className="inline-flex h-8 items-center gap-1.5 rounded-full bg-surface-2 px-3 text-xs font-semibold hover:bg-surface-3">
                <Pencil size={14} /> Edit in studio
              </Link>
            )}
          </div>
          {!e.mediaUrl && <p className="mt-3 text-sm text-muted">No media has been uploaded for this episode yet.</p>}
          {e.audioOrigin === 'AI_TTS' && <p className="mt-3 text-xs text-muted">{aiVoiceDescription(e.audioProvider, e.audioModel)}</p>}
        </div>
      </header>

      {isVideo && e.mediaUrl && (
        <div className="mt-6 overflow-hidden rounded-2xl border border-border bg-black">
          {/* eslint-disable-next-line jsx-a11y/media-has-caption -- the captions <track> is rendered below from the transcript (required to publish); the rule can't see conditional children */}
          <video
            ref={video}
            src={e.mediaUrl}
            controls
            playsInline
            preload="metadata"
            poster={e.thumbnailUrl ?? undefined}
            className="aspect-video w-full"
            onLoadedMetadata={(ev) => {
              const start = e.progress && !e.progress.completed ? e.progress.positionSeconds : 0;
              if (start > 0) ev.currentTarget.currentTime = start;
            }}
            onTimeUpdate={(ev) => {
              setVideoTime(ev.currentTarget.currentTime);
              if (!ev.currentTarget.paused) videoSaver(ev.currentTarget.currentTime);
            }}
            onPause={(ev) => videoSaver(ev.currentTarget.currentTime, { force: true })}
            onEnded={(ev) => videoSaver(ev.currentTarget.duration, { completed: true })}
            onSeeked={(ev) => isNearEnd(ev.currentTarget.currentTime, ev.currentTarget.duration) && videoSaver(ev.currentTarget.currentTime, { completed: true })}
          >
            {e.transcriptVttUrl && <track kind="subtitles" src={e.transcriptVttUrl} srcLang="en" label="English" default />}
          </video>
        </div>
      )}

      <div className="mt-8 grid gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-6">
          {e.hasTranscript ? (
            <TranscriptPanel episodeId={e.id} time={time} active={isVideo || isCurrent} onJump={jump} />
          ) : (
            <p className="rounded-2xl border border-dashed border-border p-4 text-sm text-muted">No transcript has been added to this episode.</p>
          )}

          {e.sources.length > 0 && (
            <section aria-labelledby="sources-h">
              <h2 id="sources-h" className="mb-3 text-sm font-bold">Discussions this episode draws on</h2>
              <div className="grid gap-2 sm:grid-cols-2">
                {e.sources.map((s) => (
                  <Link key={`${s.postId}-${s.commentId ?? 0}`} to={s.link} className="block rounded-xl border border-border bg-surface-2/40 p-3 hover:border-surface-3">
                    <div className="flex items-center gap-1.5 text-xs text-muted">
                      <span className="font-semibold text-text">c/{s.communitySlug}</span>
                      {s.commentId && <span className="inline-flex items-center gap-1"><MessageSquare size={11} /> comment</span>}
                    </div>
                    <div className="mt-0.5 line-clamp-1 text-sm font-semibold">{s.title}</div>
                    {s.excerpt && <p className="mt-0.5 line-clamp-2 text-xs text-muted">{s.excerpt}</p>}
                  </Link>
                ))}
              </div>
            </section>
          )}
        </div>

        <aside>
          <section aria-labelledby="chapters-h" className="rounded-2xl border border-border p-3 lg:sticky lg:top-[72px]">
            <h2 id="chapters-h" className="mb-2 px-1 text-sm font-bold">Chapters</h2>
            {e.chapters.length === 0 ? (
              <p className="px-1 text-sm text-muted">This episode has no chapters.</p>
            ) : (
              <ol className="space-y-0.5">
                {e.chapters.map((c, i) => (
                  <li key={c.id}>
                    <button
                      type="button"
                      onClick={() => jump(c.startSeconds)}
                      disabled={!e.mediaUrl}
                      aria-current={i === chapterIdx && (isVideo || isCurrent) ? 'true' : undefined}
                      className={cn('flex w-full gap-3 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-surface-2 disabled:opacity-60', i === chapterIdx && (isVideo || isCurrent) && 'bg-brand/15 font-semibold')}
                    >
                      <span className="w-14 shrink-0 text-xs tabular-nums text-brand">{formatClock(c.startSeconds)}</span>
                      <span className="min-w-0">{c.title}</span>
                    </button>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}

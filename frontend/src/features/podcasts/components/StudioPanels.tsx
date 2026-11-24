import { AlertTriangle, FileAudio, ImagePlus, Loader2, Plus, Sparkles, Trash2, Upload, X } from 'lucide-react';
import { useRef, useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/Button';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '@/services/apiError';
import type { EpisodeDetail, PodcastCapabilities, SourceRef, TranscriptFormat } from '@/types/podcasts';
import { cn } from '@/utils/format';
import { podcastApi, podcastKeys, uploadMedia, useEpisodeMutation } from '../api';
import { AiVoiceBadge } from './AiVoiceBadge';
import { countCues, formatClock, formatDuration, measureDuration, parseSourceRefs, parseTimestamp } from '../helpers';

export function Panel({ title, children, action, hint }: { title: string; children: ReactNode; action?: ReactNode; hint?: string }) {
  return (
    <section className="rounded-2xl border border-border p-4">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <h2 className="mr-auto text-sm font-bold">{title}</h2>
        {action}
      </div>
      {hint && <p className="-mt-2 mb-3 text-xs text-muted">{hint}</p>}
      {children}
    </section>
  );
}

const MIME_EXT: Record<string, string> = {
  'audio/mpeg': '.mp3', 'audio/mp4': '.m4a', 'audio/ogg': '.ogg', 'audio/wav': '.wav', 'audio/x-wav': '.wav',
  'video/mp4': '.mp4', 'video/webm': '.webm',
};

/** Media upload: measures duration in the browser, then uploads with a progress bar (cancellable). */
export function MediaPanel({ e, caps, locked }: { e: EpisodeDetail; caps?: PodcastCapabilities; locked: boolean }) {
  const qc = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const abort = useRef<AbortController | null>(null);
  const types = [...(caps?.audioTypes ?? []), ...(caps?.videoTypes ?? [])];
  const accept = types.length ? [...types, ...types.map((t) => MIME_EXT[t]).filter(Boolean)].join(',') : 'audio/*,video/mp4,video/webm';

  const pick = async (file: File) => {
    if (caps && file.size > caps.maxUploadMb * 1024 * 1024) {
      toast.error(`Files must be under ${caps.maxUploadMb} MB`);
      return;
    }
    let duration: number;
    try {
      duration = await measureDuration(file);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Could not read this file');
      return;
    }
    abort.current = new AbortController();
    setProgress(0);
    try {
      const d = await uploadMedia(e.id, file, duration, setProgress, abort.current.signal);
      qc.setQueryData(podcastKeys.episode(e.id), d);
      qc.invalidateQueries({ queryKey: ['podcasts', 'studio'] });
      toast.success(`Uploaded ${formatDuration(duration)} of ${d.mediaKind === 'VIDEO' ? 'video' : 'audio'}`);
    } catch (err) {
      if (!(err instanceof DOMException && err.name === 'AbortError')) toast.error(errorMessage(err));
    } finally {
      setProgress(null);
      abort.current = null;
      if (input.current) input.current.value = '';
    }
  };

  return (
    <Panel title="Media" hint={caps ? `Audio: MP3, M4A, OGG, WAV · Video: MP4, WebM · up to ${caps.maxUploadMb} MB` : undefined}>
      {caps?.audioGeneration ? (
        <p className="mb-3 text-xs text-muted">Upload a recording, or generate audio from the script (labelled as an AI-generated voice). Video generation isn't available.</p>
      ) : (
        <div className="mb-3 flex gap-2 rounded-xl border border-warning/30 bg-warning/10 px-3 py-2 text-xs">
          <AlertTriangle size={14} className="mt-0.5 shrink-0 text-warning" />
          Audio/video generation isn't configured. Record or produce the episode and upload it.
        </div>
      )}
      {e.mediaUrl ? (
        <div className="space-y-2">
          {/* Studio preview of the moderator's own upload, before a transcript may exist. Published episodes show the
              transcript as captions (video) and as a timed transcript (audio) on the episode page. */}
          {e.mediaKind === 'VIDEO' ? (
            // eslint-disable-next-line jsx-a11y/media-has-caption -- studio preview of an upload; captions are added on the episode page
            <video src={e.mediaUrl} controls preload="metadata" className="aspect-video w-full rounded-xl bg-black" />
          ) : (
            // eslint-disable-next-line jsx-a11y/media-has-caption -- studio preview of an upload; the transcript is imported below
            <audio src={e.mediaUrl} controls preload="metadata" className="w-full" />
          )}
          <p className="flex flex-wrap items-center gap-2 text-xs text-muted">
            {e.mediaKind === 'VIDEO' ? 'Video' : 'Audio'} · {formatDuration(e.durationSeconds)} · {e.mediaContentType}
            {e.audioOrigin === 'AI_TTS' ? <AiVoiceBadge provider={e.audioProvider} model={e.audioModel} size="xs" /> : <span>· Uploaded</span>}
          </p>
        </div>
      ) : (
        <p className="text-sm text-muted">No media uploaded yet.</p>
      )}
      {progress != null ? (
        <div className="mt-3">
          <div className="flex items-center justify-between text-xs text-muted">
            <span>Uploading… {Math.round(progress * 100)}%</span>
            <button type="button" onClick={() => abort.current?.abort()} className="inline-flex items-center gap-1 font-semibold hover:text-text"><X size={12} /> Cancel</button>
          </div>
          <div className="mt-1 h-2 overflow-hidden rounded-full bg-surface-3" role="progressbar" aria-valuenow={Math.round(progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Upload progress">
            <div className="h-full bg-brand transition-[width]" style={{ width: `${progress * 100}%` }} />
          </div>
        </div>
      ) : (
        <div className="mt-3">
          <input ref={input} type="file" accept={accept} className="sr-only" id={`media-${e.id}`} disabled={locked} onChange={(ev) => ev.target.files?.[0] && pick(ev.target.files[0])} />
          <label htmlFor={`media-${e.id}`} className={cn('inline-flex h-9 cursor-pointer items-center gap-2 rounded-full bg-surface-2 px-4 text-sm font-semibold hover:bg-surface-3', locked && 'pointer-events-none opacity-50')}>
            {e.mediaUrl ? <Upload size={15} /> : <FileAudio size={15} />} {e.mediaUrl ? 'Replace media' : 'Upload media'}
          </label>
          {locked && <p className="mt-2 text-xs text-muted">Unpublish the episode to change its media.</p>}
        </div>
      )}
    </Panel>
  );
}

export function ThumbnailPanel({ e }: { e: EpisodeDetail }) {
  const m = useEpisodeMutation(e.id, (f: File) => podcastApi.thumbnail(e.id, f));
  return (
    <Panel title="Cover image">
      <div className="flex items-center gap-4">
        {e.thumbnailUrl ? <img src={e.thumbnailUrl} alt="" className="h-24 w-24 rounded-xl object-cover" /> : <span className="flex h-24 w-24 items-center justify-center rounded-xl bg-surface-2 text-faint"><ImagePlus size={22} /></span>}
        <div>
          <input type="file" accept="image/png,image/jpeg,image/webp" className="sr-only" id={`thumb-${e.id}`} onChange={(ev) => {
            const f = ev.target.files?.[0];
            if (f) m.mutate(f, { onSuccess: () => toast.success('Cover updated'), onError: (er) => toast.error(errorMessage(er)) });
            ev.target.value = '';
          }} />
          <label htmlFor={`thumb-${e.id}`} className="inline-flex h-9 cursor-pointer items-center gap-2 rounded-full bg-surface-2 px-4 text-sm font-semibold hover:bg-surface-3">
            {m.isPending ? <Loader2 size={15} className="animate-spin" /> : <ImagePlus size={15} />} {e.thumbnailUrl ? 'Change' : 'Upload'}
          </label>
          <p className="mt-1 text-xs text-muted">Square PNG, JPEG or WebP works best.</p>
        </div>
      </div>
    </Panel>
  );
}

interface ChapterRow { key: number; time: string; title: string }

/** Chapters: editable rows with m:ss / h:mm:ss times; saved sorted by start time. */
export function ChaptersPanel({ e }: { e: EpisodeDetail }) {
  const [rows, setRows] = useState<ChapterRow[]>(() => e.chapters.map((c, i) => ({ key: i, time: formatClock(c.startSeconds), title: c.title })));
  const [nextKey, setNextKey] = useState(e.chapters.length);
  const m = useEpisodeMutation(e.id, (chapters: { startSeconds: number; title: string }[]) => podcastApi.update(e.id, { chapters }));
  const invalid = rows.filter((r) => r.title.trim() && parseTimestamp(r.time) == null).length;
  const save = () => {
    const chapters = rows
      .filter((r) => r.title.trim())
      .map((r) => ({ startSeconds: parseTimestamp(r.time) ?? 0, title: r.title.trim() }))
      .sort((a, b) => a.startSeconds - b.startSeconds);
    m.mutate(chapters, {
      onSuccess: (d) => {
        if (d) setRows(d.chapters.map((c, i) => ({ key: i, time: formatClock(c.startSeconds), title: c.title })));
        toast.success('Chapters saved');
      },
      onError: (er) => toast.error(errorMessage(er)),
    });
  };
  return (
    <Panel title="Chapters" hint="Optional. Times like 0:00, 12:30 or 1:05:00.">
      <div className="space-y-2">
        {rows.map((r, i) => (
          <div key={r.key} className="flex items-center gap-2">
            <Input aria-label={`Chapter ${i + 1} start`} value={r.time} onChange={(ev) => setRows(rows.map((x) => (x.key === r.key ? { ...x, time: ev.target.value } : x)))} className={cn('w-24 tabular-nums', r.title.trim() && parseTimestamp(r.time) == null && 'border-danger')} placeholder="0:00" />
            <Input aria-label={`Chapter ${i + 1} title`} value={r.title} onChange={(ev) => setRows(rows.map((x) => (x.key === r.key ? { ...x, title: ev.target.value } : x)))} maxLength={200} placeholder="Chapter title" />
            <button type="button" aria-label={`Remove chapter ${i + 1}`} onClick={() => setRows(rows.filter((x) => x.key !== r.key))} className="rounded-full p-2 text-muted hover:bg-surface-2 hover:text-danger"><Trash2 size={15} /></button>
          </div>
        ))}
        {rows.length === 0 && <p className="text-sm text-muted">No chapters yet.</p>}
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button variant="secondary" size="sm" onClick={() => { setRows([...rows, { key: nextKey, time: rows.length ? '' : '0:00', title: '' }]); setNextKey(nextKey + 1); }} disabled={rows.length >= 100}>
          <Plus size={14} /> Add chapter
        </Button>
        <Button size="sm" loading={m.isPending} disabled={invalid > 0} onClick={save}>Save chapters</Button>
        {invalid > 0 && <span className="text-xs text-danger">{invalid} time{invalid === 1 ? '' : 's'} not valid</span>}
      </div>
    </Panel>
  );
}

/** Transcript import from VTT, SRT or "[hh:mm:ss] Speaker: text" lines; shows the resulting count. */
export function TranscriptPanelEditor({ e, locked }: { e: EpisodeDetail; locked: boolean }) {
  const qc = useQueryClient();
  const [format, setFormat] = useState<TranscriptFormat>('vtt');
  const [content, setContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<number | null>(null);
  const preview = content ? countCues(format, content) : 0;
  const submit = async () => {
    setBusy(true);
    try {
      const r = await podcastApi.transcriptImport(e.id, format, content);
      setResult(r.segments);
      setContent('');
      qc.invalidateQueries({ queryKey: podcastKeys.episode(e.id) });
      qc.invalidateQueries({ queryKey: ['podcasts', 'transcript', e.id] });
      toast.success(`Imported ${r.segments} transcript segments`);
    } catch (err) {
      toast.error(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Panel title="Transcript" hint="Required before publishing. Importing replaces the current transcript." action={e.hasTranscript ? <span className="text-xs font-semibold text-accent">✓ Transcript added{result != null ? ` (${result} segments)` : ''}</span> : undefined}>
      <div className="flex flex-wrap items-center gap-2">
        <label className="text-xs font-semibold text-muted" htmlFor={`tf-${e.id}`}>Format</label>
        <Select id={`tf-${e.id}`} value={format} onChange={(ev) => setFormat(ev.target.value as TranscriptFormat)} className="h-8 w-48 text-sm">
          <option value="vtt">WebVTT (.vtt)</option>
          <option value="srt">SubRip (.srt)</option>
          <option value="timestamped">Timestamped text</option>
        </Select>
        <input type="file" accept=".vtt,.srt,.txt,text/plain,text/vtt" id={`tfile-${e.id}`} className="sr-only" onChange={async (ev) => {
          const f = ev.target.files?.[0];
          if (!f) return;
          const text = await f.text();
          setContent(text);
          if (/\.vtt$/i.test(f.name)) setFormat('vtt');
          else if (/\.srt$/i.test(f.name)) setFormat('srt');
          else setFormat('timestamped');
          ev.target.value = '';
        }} />
        <label htmlFor={`tfile-${e.id}`} className="inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-full bg-surface-2 px-3 text-xs font-semibold hover:bg-surface-3"><Upload size={13} /> Load file</label>
      </div>
      <Textarea
        value={content}
        onChange={(ev) => setContent(ev.target.value)}
        rows={8}
        className="mt-3 font-mono text-xs"
        aria-label="Transcript content"
        placeholder={format === 'timestamped' ? '[00:00:00] Host: Welcome to the show…\n[00:01:12] Guest: Thanks for having me…' : 'WEBVTT\n\n00:00:00.000 --> 00:00:04.000\nWelcome to the show…'}
        disabled={locked}
      />
      <div className="mt-2 flex items-center gap-3">
        <Button size="sm" loading={busy} disabled={!content.trim() || locked || preview === 0} onClick={submit}>Import transcript</Button>
        {content && <span className={cn('text-xs', preview ? 'text-muted' : 'text-danger')}>{preview ? `${preview} timed lines detected` : 'No timed lines detected in this format'}</span>}
        {locked && <span className="text-xs text-muted">Unpublish to change the transcript.</span>}
      </div>
    </Panel>
  );
}

export function SourcesPanel({ e }: { e: EpisodeDetail }) {
  const [text, setText] = useState('');
  const m = useEpisodeMutation(e.id, (sources: SourceRef[]) => podcastApi.update(e.id, { sources }));
  const current: SourceRef[] = e.sources.map((s) => (s.commentId ? { postId: s.postId, commentId: s.commentId } : { postId: s.postId }));
  const add = () => {
    const { refs, invalid } = parseSourceRefs(text);
    if (invalid.length) {
      toast.error(`Couldn't read: ${invalid.slice(0, 3).join(', ')}`);
      return;
    }
    const merged = [...current];
    for (const r of refs) if (!merged.some((x) => x.postId === r.postId && (x.commentId ?? 0) === (r.commentId ?? 0))) merged.push(r);
    m.mutate(merged.slice(0, 50), { onSuccess: () => { setText(''); toast.success('Sources updated'); }, onError: (er) => toast.error(errorMessage(er)) });
  };
  const remove = (i: number) => m.mutate(current.filter((_, j) => j !== i), { onError: (er) => toast.error(errorMessage(er)) });
  return (
    <Panel title="Sources" hint="Optional. The discussions this episode draws on, linked from the episode page. Paste post links (e.g. /c/programming/p/12#c-34) or IDs, one per line.">
      {e.sources.length > 0 && (
        <ul className="mb-3 space-y-1.5">
          {e.sources.map((s, i) => (
            <li key={`${s.postId}-${s.commentId ?? 0}`} className="flex items-center gap-2 rounded-xl bg-surface-2/60 px-3 py-2 text-sm">
              <span className="min-w-0 flex-1 truncate"><span className="font-semibold">c/{s.communitySlug}</span> · {s.title}{s.commentId ? ' (comment)' : ''}</span>
              <button type="button" aria-label={`Remove source ${s.title}`} onClick={() => remove(i)} className="rounded-full p-1.5 text-muted hover:bg-surface-3 hover:text-danger"><X size={13} /></button>
            </li>
          ))}
        </ul>
      )}
      <Textarea value={text} onChange={(ev) => setText(ev.target.value)} rows={3} aria-label="Post links to add" placeholder="/c/programming/p/1&#10;/c/programming/p/1#c-3" />
      <Button size="sm" className="mt-2" loading={m.isPending} disabled={!text.trim()} onClick={add}>Add sources</Button>
    </Panel>
  );
}

/** Script editor with optional AI drafting (background job; the page polls while GENERATING). */
export function ScriptPanel({ e, caps }: { e: EpisodeDetail; caps?: PodcastCapabilities }) {
  const [script, setScript] = useState(e.script ?? '');
  const [dirty, setDirty] = useState(false);
  const [topic, setTopic] = useState(e.title);
  const [minutes, setMinutes] = useState(45);
  const save = useEpisodeMutation(e.id, (s: string) => podcastApi.update(e.id, { script: s }));
  const draft = useEpisodeMutation(e.id, () => podcastApi.draftScript(e.id, topic.trim(), minutes));
  const generating = e.scriptStatus === 'GENERATING';
  const [seen, setSeen] = useState(e.script);
  if (e.script !== seen && !dirty) {
    // A new draft arrived (or a save round-tripped): show it unless the editor has unsaved edits.
    setSeen(e.script);
    setScript(e.script ?? '');
  }

  return (
    <Panel
      title="Script"
      hint="Optional working script for hosts. It isn't shown to listeners."
      action={e.scriptSource === 'AI_DRAFT' ? <span className="rounded-full bg-brand/15 px-2 py-0.5 text-[11px] font-bold text-brand">AI draft. Review before publishing</span> : undefined}
    >
      {caps?.aiScriptDrafting ? (
        <div className="mb-3 rounded-xl border border-brand/30 bg-brand/[0.06] p-3">
          <div className="flex items-center gap-2 text-sm font-semibold"><Sparkles size={15} className="text-brand" /> Draft with AI</div>
          <p className="mt-0.5 text-xs text-muted">Builds a cited, multi-perspective outline and script from this community's discussions. You edit and approve it, and it never becomes audio by itself.</p>
          <div className="mt-2 flex flex-wrap items-end gap-2">
            <label className="min-w-[220px] flex-1 text-xs font-semibold text-muted">Topic<Input value={topic} onChange={(ev) => setTopic(ev.target.value)} maxLength={300} className="mt-1" /></label>
            <label className="text-xs font-semibold text-muted">Target length
              <Select value={minutes} onChange={(ev) => setMinutes(Number(ev.target.value))} className="mt-1 h-10 w-36">
                {[10, 20, 30, 45, 60, 75, 90].map((m) => <option key={m} value={m}>{m} minutes</option>)}
              </Select>
            </label>
            <Button
              loading={draft.isPending}
              disabled={generating || !topic.trim()}
              onClick={() => {
                if (dirty && !window.confirm('Replace your unsaved script edits with a new AI draft?')) return;
                setDirty(false);
                draft.mutate(undefined, { onSuccess: () => toast.info('Drafting… this can take a minute or two'), onError: (er) => toast.error(errorMessage(er)) });
              }}
            >
              <Sparkles size={15} /> {e.script ? 'Redraft' : 'Draft script'}
            </Button>
          </div>
        </div>
      ) : (
        <p className="mb-3 rounded-xl bg-surface-2/60 px-3 py-2 text-xs text-muted">AI drafting isn't configured on this server. Write the script yourself, or leave it empty.</p>
      )}
      {generating && (
        <div className="mb-3 flex items-center gap-2 rounded-xl bg-surface-2/60 px-3 py-2 text-sm" role="status">
          <Loader2 size={15} className="animate-spin text-brand" /> Reading the community's discussions and writing a draft…
        </div>
      )}
      {e.scriptStatus === 'FAILED' && e.scriptError && (
        <div className="mb-3 flex gap-2 rounded-xl border border-danger/30 bg-danger/10 px-3 py-2 text-sm"><AlertTriangle size={15} className="mt-0.5 text-danger" /> {e.scriptError}</div>
      )}
      <Textarea
        value={script}
        onChange={(ev) => { setScript(ev.target.value); setDirty(true); }}
        rows={18}
        className="font-mono text-[13px] leading-relaxed"
        aria-label="Episode script (Markdown)"
        placeholder="# Cold open&#10;HOST A: …"
        disabled={generating}
      />
      <div className="mt-2 flex items-center gap-3">
        <Button size="sm" loading={save.isPending} disabled={!dirty || generating} onClick={() => save.mutate(script, { onSuccess: () => { setDirty(false); toast.success('Script saved'); }, onError: (er) => toast.error(errorMessage(er)) })}>Save script</Button>
        <span className="text-xs text-faint">{script.trim() ? `${script.trim().split(/\s+/).length.toLocaleString()} words (about ${Math.max(1, Math.round(script.trim().split(/\s+/).length / 150))} min spoken)` : 'Empty'}</span>
        {dirty && <span className="text-xs text-warning">Unsaved changes</span>}
      </div>
    </Panel>
  );
}
